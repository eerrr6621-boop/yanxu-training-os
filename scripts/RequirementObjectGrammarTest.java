package com.training;

import java.util.*;

/** Literal, domain-independent demand roles; no model or acceptance labels. */
public final class RequirementObjectGrammarTest {
    static int checks;
    static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    static Map<String,Object> assess(String query,String text) {
        Map<String,Object> candidate=new LinkedHashMap<>();
        candidate.put("professional_score",50.0);candidate.put("_rule_relevant",true);
        candidate.put("gaps",new ArrayList<String>());
        new RequirementCoverage(query).assess(candidate,text,true);return candidate;
    }
    static String event(String topic,String role,String state) {
        return "课程名称："+topic+"\n授课人：本人\n授课角色："+role+"\n完成状态："+state;
    }
    public static void main(String[] args) {
        for(String topic:List.of("竹片含水率记录","展柜锁芯编号","纸浆纤维分级")) {
            for(String prefix:List.of("我们下次想讲","客户这次需要讲授","接下来计划教","下一次希望讲")) {
                String query=prefix+topic;
                RequirementTopic.Result parsed=RequirementTopic.parse(query);
                check(parsed.courses().size()==1,"explicit teaching intent has one content object: "+query);
                check(parsed.courses().get(0).canonicalAnchor().equals(topic),"literal unfamiliar course name retained");
                check(parsed.courses().get(0).historyConditions().isEmpty(),"future demand is not completed teaching history");
                check(parsed.consumedSpans().stream().noneMatch(x->x.kind().equals("narrative_audience")),"no invented audience");
                for(RequirementTopic.Consumption c:parsed.consumedSpans())
                    check(query.substring(c.span().start(),c.span().end()).equals(c.span().text()),"exact original consumption span");
            }
            String query="下一次需要讲《"+topic+"》。老师必须已经自己把这门课教完过";
            RequirementTopic.Result parsed=RequirementTopic.parse(query);
            check(parsed.courses().size()==1,"post-object completion binds sole declared course");
            check(parsed.courses().get(0).requiresHistory(),"past completion remains a hard history requirement");
            check(new RequirementCoverage(query).required.keySet().stream().noneMatch(k->k.contains("完过")),"action suffix is never invented as a course");
            check(Boolean.TRUE.equals(assess(query,event(topic,"独立主讲","已完成")).get("_admitted")),"same literal completed event satisfies request");
            for(String[] invalid:List.of(new String[]{"助教","已完成"},new String[]{"独立主讲","计划中"}))
                check(!Boolean.TRUE.equals(assess(query,event(topic,invalid[0],invalid[1])).get("_admitted")),"role/state cannot borrow history from a request");
            String ambiguous="培训主题：《"+topic+"》和《玻璃纤维分类》；老师必须已经自己把这门课教完过";
            check(RequirementTopic.parse(ambiguous).courses().stream().noneMatch(RequirementTopic.Course::requiresHistory),"two courses do not provide singular antecedent");
            for(String suffix:List.of("教完过","教授过","讲完过")) {
                RequirementCoverage terminal=new RequirementCoverage("老师已经自己把同一内容"+suffix);
                check(terminal.required.keySet().stream().noneMatch(k->k.matches(".*(?:完过|授过|讲完过)$")),"terminal action must not backtrack into a fictitious object: "+suffix);
            }
            for(String prefix:List.of("不要讲","如果需要讲","只有持证才需要讲","已讲授","曾经教过"))
                check(RequirementNarrative.declaration(prefix+topic)==null,"negation/condition/past are not future course declarations");
            for(String restriction:List.of("但只接受持证人","但仅限有认证者","且只能有十年教学经验的人来讲","只接受持证人","并且必须实际教过","不少于十年经历")) {
                String conditional="下一次想讲"+topic+restriction;
                check(RequirementNarrative.declaration(conditional)==null,"restriction is not opaque course text");
                check(!RequirementTopic.parse(conditional).unparsedClauses().isEmpty(),"whole original restriction stays observable");
                check(!Boolean.TRUE.equals(assess(conditional,event(topic+restriction,"独立主讲","已完成")).get("_admitted")),"a title repeating a condition cannot prove that condition");
            }
        }
        check(RequirementNarrative.declaration("我们下次想安排付款")==null,"administrative action is not teaching");
        check(RequirementNarrative.declaration("下一次想讲设备标识但必须持证")==null,"embedded hard qualification must not become course text");
        check(RequirementNarrative.declaration("下一次想讲这门课")==null,"unresolved reference is not a new named topic");
        check(RequirementTopic.parse("老师必须已经自己把这门课教完过").courses().isEmpty(),"no course antecedent is not guessed");
        System.out.println("Requirement object grammar: "+checks+" assertions passed");
    }
}
