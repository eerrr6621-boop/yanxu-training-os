package com.training;

import java.util.*;
import java.nio.charset.StandardCharsets;

/** Synthetic source tests; capability is not certification or teaching history. */
public final class CapabilitySentenceTest {
    static int checked;
    static void check(boolean ok,String label){checked++;if(!ok)throw new AssertionError(label);}
    static Map<String,Object> meta(String text){
        List<Map<String,Object>> sections=EvidenceSections.sections(text);
        return new LinkedHashMap<>(Map.of("evidence_complete",false,"scoped_evidence_complete",!sections.isEmpty(),
            "scoring_sections",sections,"scoring_scope",EvidenceSections.versionFor(sections)));
    }
    static Map<String,Object> candidate(String source,String query){
        Map<String,Object> c=new LinkedHashMap<>();c.put("gaps",new ArrayList<String>());c.put("semantic",meta(source));
        c.put("professional_score",0);c.put("_rule_relevant",true);
        new RequirementCoverage(query).assess(c,source,false);return c;
    }
    static List<Map<String,Object>> capability(String source){
        return EvidenceSections.sections(source).stream().filter(s->"capability_sentence_v1".equals(s.get("kind"))).toList();
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception{
        if(args.length>0&&args[0].equals("--inspect")){
            List<Object> input=(List<Object>)Json.parse(new String(System.in.readAllBytes(),StandardCharsets.UTF_8));
            List<Object> result=new ArrayList<>();
            for(Object value:input){String source=LocalSemantic.normalized((String)value);result.add(Map.of("text",source,"sections",EvidenceSections.sections(source),
                "records",ProfessionalEvidence.records(source)));}System.out.println(Json.write(result));return;
        }
        for(String topic:List.of("客户服务","古籍页码核对","园艺工具清洁")){
            String claim="同时作为"+topic+"认证讲师,擅长用故事讲解"+topic+"课程。";
            String participation="参与社区志愿服务计划,担任服务项目认证讲师。";
            String line="专业能力:持有普通职业证书。"+claim+participation;
            for(String body:List.of(line,participation+claim)){
                String source="个人介绍\n"+body+"\n精品课程\n无关主题";
                List<Map<String,Object>> sections=capability(source);
                check(sections.size()==1,"one bounded capability "+topic);
                check(EvidenceSections.verified(source,meta(source)),"scope recomputes");
                check(EvidenceSections.containsQuote(source,meta(source),claim),"exact original capability quote");
                check(!EvidenceSections.containsQuote(source,meta(source),participation),"participation not recovered");
                Map<String,Object> record=ProfessionalEvidence.records(source).stream().filter(r->claim.equals(r.get("text"))).findFirst().orElseThrow();
                check("course_statement".equals(record.get("role")),"capability is not teaching event");
                check("content_only".equals(record.get("scope_evidence_limit")),"explicit content-only limit");
                check(body.equals(record.get("context_text")),"full physical line retained");
                check(Boolean.FALSE.equals(record.get("verified")),"certification remains unverified");
                int at=((Number)record.get("source_offset")).intValue();check(source.substring(at,at+claim.length()).equals(claim),"source offset exact");
                check(Boolean.TRUE.equals(candidate(source,"培训主题："+topic).get("_admitted")),"capability supports content only");
                check(!Boolean.TRUE.equals(candidate(source,"培训主题："+topic+"；必须本人实际授课").get("_admitted")),"no actual history invented");
                check(!Boolean.TRUE.equals(candidate(source,"培训主题："+topic+"；必须具备授课资格").get("_admitted")),"no verified qualification invented");
                Map<String,Object> forgery=meta(source);List<Map<String,Object>> ranges=new ArrayList<>();
                for(Map<String,Object> s:sections){Map<String,Object> changed=new LinkedHashMap<>(s);changed.put("context_start",changed.get("source_start"));ranges.add(changed);}
                forgery.put("scoring_sections",ranges);check(!EvidenceSections.verified(source,forgery),"cannot hide containing context");
            }
            for(String bad:List.of("本人没有上述能力。","上述为模板内容。","上述是同事的能力介绍。","本人仅承担助教工作。")){
                check(capability("个人介绍\n"+line+bad).isEmpty(),"later qualifier blocks recovery");
                check(!Boolean.TRUE.equals(candidate("个人介绍\n"+line+bad,"培训主题："+topic).get("_admitted")),"later qualifier blocks admission");
            }
            for(String heading:List.of("参训经历","助教经历","计划课程","团队案例"))
                check(capability(heading+"\n"+line).isEmpty(),"excluded heading "+heading);
            for(String badClaim:List.of("张老师擅长"+topic+"课程。","她擅长"+topic+"课程。","据说本人擅长"+topic+"课程。","假如本人擅长"+topic+"课程。","“本人擅长"+topic+"课程”。"))
                check(capability("个人介绍\n"+badClaim+participation).isEmpty(),"authorship/quotation not recovered");
            check(capability("个人介绍\n本人参与"+topic+"课程认证培训,擅长相关课程。").isEmpty(),"no splitting comma participation clause");
        }
        for(String space:List.of("\n","\r\n","\n  \n","\n\t\n")){
            String source="个人介绍\n《无关课程》"+space+"精品课程\n无关主题";
            check(capability(source).isEmpty(),"blank intro lines have no inverted source range");
        }
        System.out.println("Capability sentences: "+checked+" checks passed; synthetic only");
    }
}
