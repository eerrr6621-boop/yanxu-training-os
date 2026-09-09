package com.training;

import java.util.*;

/** Synthetic grammar/offset/closed-failure checks, not model accuracy. */
public final class PostposedExclusionTest {
    static int checks;
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    static List<String> quotes(String source){return PostposedExclusion.spans(source).stream().map(s->source.substring(s.start(),s.end())).toList();}
    public static void main(String[] args) throws Exception {
        if(args.length>0&&args[0].equals("protocol")) {
            Object input=Json.parse(new String(System.in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            List<Object> result=new ArrayList<>();
            for(Object raw:(List<?>)input){String q=(String)raw;RequirementConstraints c=new RequirementConstraints(q);
                result.add(Map.of("source",q,"spans",PostposedExclusion.spans(q).stream().map(s->Map.of("start",q.codePointCount(0,s.start()),"end",q.codePointCount(0,s.end()),"text",q.substring(s.start(),s.end()))).toList(),
                    "features",c.features,"positive",c.positive,"focused_query",LocalSemantic.focusedQuery(q),"review",c.unknownExclusion));}
            System.out.print(Json.write(result));return;
        }
        for(String topic:List.of("古籍页码核对","冷链温度记录","园艺工具清洁"))
        for(String negative:List.of("不算","不计入","不计作","不作为","不满足","不能视为","不能算作","不能代替"))
        for(String comma:List.of("，",",",",\n","，\r\n  ")) {
            String prefix="培训主题：《"+topic+"》。必须面授。",body="只有学员现场练习而教师远程讲解"+comma+negative+"所需现场面授经历";
            String q=prefix+body+"。必须中文授课。";List<PostposedExclusion.Span> spans=PostposedExclusion.spans(q);
            check(quotes(q).equals(List.of(body)),"literal comma-linked antecedent "+q);
            String masked=PostposedExclusion.masked(q,spans);
            check(masked.length()==q.length()&&masked.indexOf("必须中文授课")==q.indexOf("必须中文授课"),"offsets stable");
            check(masked.startsWith(prefix)&&!masked.contains("远程"),"unrelated positives retained");
            RequirementConstraints c=new RequirementConstraints(q);
            check(!c.features.containsKey("远程授课形式")&&c.features.containsKey("面对面授课形式")&&c.features.containsKey("中文授课"),"no mode reversal");
            check(c.unknownExclusion&&!c.positive.contains("远程"),"rejection preserved as pending, not resolved");
            RequirementTopic.Result parsed=RequirementTopic.parse(q);
            check(parsed.unparsedClauses().stream().anyMatch(r->r.reason().equals("postposed_exclusion_requires_review")&&r.span().text().equals(body)),"full original rejection retained");
            Map<String,Object> candidate=new LinkedHashMap<>();candidate.put("_rule_relevant",true);candidate.put("professional_score",100);candidate.put("gaps",new ArrayList<String>());
            candidate.put("semantic",Map.of("similarity",.999,"evidence_complete",true));
            new RequirementCoverage(q).assess(candidate,"本人实际主讲过《"+topic+"》课程，采用中文线下面授。",true);
            check(!Boolean.TRUE.equals(candidate.get("_admitted")),"high score cannot discharge unparsed rejection");
        }
        String repeat="培训主题：工具清洁。必须远程授课。必须远程授课，不算现场经历。";
        check(new RequirementConstraints(repeat).positive.contains("必须远程授课"),"identical words elsewhere not erased");
        check(new RequirementConstraints(repeat).features.containsKey("远程授课形式"),"separate affirmative remains");
        for(String wrap:List.of("\n","\r\n","\n\n")) {
            String q="培训主题：《工具清洁》\n师资要求：教师远程授课，"+wrap+"不算现场面授经历。\n必须面授。";
            String focused=LocalSemantic.focusedQuery(q);
            check(PostposedExclusion.spans(focused).size()==1,"projection retains comma-linked rejection over wrapped field");
            check(PostposedExclusion.masked(focused,PostposedExclusion.spans(focused)).contains("必须面授"),"following independent requirement retained");
        }
        for(String boundary:List.of("。","；",";","\n","\r\n","！","?")) {
            String q="需要远程授课"+boundary+"不算现场经历";
            check(PostposedExclusion.spans(q).isEmpty(),"no antecedent invented across hard boundary");
        }
        for(String q:List.of("《远程授课，不算现场面授经历》","“远程授课，不算现场面授经历”","（远程授课，不算现场面授经历）","\"远程授课，不算现场面授经历\"",
                "远程授课，不算", "远程授课，不算   ", "远程授课，\n师资要求：不算现场经历", "远程授课（，不算现场经历"))
            check(PostposedExclusion.spans(q).isEmpty(),"quoted/incomplete/cross-field scope not inferred: "+q);
        String last="必须中文授课，教师远程授课，不算现场经历。";
        check(quotes(last).equals(List.of("教师远程授课，不算现场经历")),"only adjacent antecedent masked");
        String excludedTitle="培训主题：《古籍页码核对》。主讲过《线上演练》，不算所需课程经历。";
        check(RequirementTopic.parse(excludedTitle).courses().stream().noneMatch(c->c.canonicalAnchor().contains("线上演练")),"rejected course not positive course");
        check(new RequirementCoverage(excludedTitle).required.keySet().stream().noneMatch(s->s.contains("线上演练")),"legacy topic extraction cannot resurrect rejected course");
        for(String field:List.of("必须英文授课","至少3年授课经历","已取得讲师资格","授课对象：老年学员")) {
            String q="培训主题：《工具清洁》。"+field+"，不算所需经历。";
            RequirementConstraints c=new RequirementConstraints(q);
            check(c.unknownExclusion,"all unresolved rejection semantics pending");
            check(!c.features.containsKey("英文授课")&&!c.features.containsKey("老年学员")&&c.minimums.isEmpty()&&!c.qualificationRequired,"rejected antecedent not positive feature/count/qualification");
        }
        String unicode="培训主题：《工具清洁》\n教师远程授课🎥，不算现场经历。\n培训对象：新任管理员";
        AudienceIntent intent=new RequirementConstraints(unicode).audienceIntent;
        check(intent.source.equals(unicode),"audit source stays literal, not the positive-feature mask");
        check(intent.contexts.size()==1,"independent future audience retained after excluded supplementary character");
        Map<String,Object> audit=intent.audit(intent.contexts.get(0));
        Map<?,?> span=(Map<?,?>)audit.get("requirement_source_span");
        check(audit.get("requirement_matching_text").equals(unicode),"matching-text ledger does not silently mask rejection");
        check(span.get("text").equals("新任管理员")&&((Number)span.get("source_start")).intValue()==unicode.codePointCount(0,unicode.indexOf("新任管理员")),"code-point offset computed from unmodified source");
        System.out.println("Postposed exclusions: "+checks+" synthetic checks PASS; no inferred enforcement.");
    }
}
