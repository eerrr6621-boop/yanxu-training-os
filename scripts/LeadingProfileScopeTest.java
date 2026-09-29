package com.training;
import java.util.*;

/** New author-fixed synthetic list pairs, not a model/holdout evaluation. */
public final class LeadingProfileScopeTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static String text(String topic){return "✓ "+topic+"\n✓ 分类标记整理\n个人介绍\n本人参加过陶艺课程培训。\n精品课程\n纸张对折课程";}
    static List<Map<String,Object>> leading(String source){return EvidenceSections.sections(source).stream().filter(s->"leading_profile_list_v1".equals(s.get("kind"))).toList();}
    static Map<String,Object> metadata(String source){return new LinkedHashMap<>(Map.of("evidence_complete",false,"scoped_evidence_complete",true,
        "scoring_scope","independent_profiles_v1","scoring_sections",EvidenceSections.sections(source)));}
    static boolean admitted(String query,String source){Map<String,Object> c=new LinkedHashMap<>();c.put("professional_score",20.0);c.put("_rule_relevant",true);
        c.put("gaps",new ArrayList<String>());c.put("semantic",metadata(source));new RequirementCoverage(query).assess(c,source,false);return Boolean.TRUE.equals(c.get("_admitted"));}
    public static void main(String[] args){
        for(String topic:List.of("岩样编号核对","皮革裁片归档","酿造桶号标识")) {
            String source=text(topic);List<Map<String,Object>> scopes=leading(source);check(scopes.size()==1,"bounded leading list recovered "+topic);
            Map<String,Object> scope=scopes.get(0),meta=metadata(source);
            check(scope.get("heading").equals(""),"no synthetic heading");
            check(scope.get("heading_start").equals(scope.get("heading_end")),"empty heading range");
            check(EvidenceSections.verified(source,meta),"Java independently revalidates scope");
            check(EvidenceSections.versionFor(EvidenceSections.sections(source)).equals("independent_profiles_v1"),"new scope requires new protocol");
            // Scope recovery does not add arbitrary noun-topic understanding to
            // Coverage. The first draft incorrectly expected final admission;
            // that failed assertion/source is preserved in the private report.
            check(EvidenceSections.containsQuote(source,meta,"✓ "+topic),"original professional text is valid scoped retrieval");
            check(!admitted("培训主题："+topic+"；必须有本人实际授课记录",source),"content list cannot become teaching history");
            check(!admitted("培训主题："+topic+"；必须面向老年学员",source),"topic label not an audience");
            String masked=EvidenceSections.maskedSource(source,meta);
            check(masked.indexOf(topic)==source.indexOf(topic),"original source position unchanged");
            check(!masked.contains("参加过"),"unknown attendance remains outside scope");
            check(ProfessionalEvidence.records(masked).stream().filter(r->r.get("text").toString().contains(topic)).noneMatch(r->r.get("role").equals("teaching_history_statement")),"role is not upgraded");
            for(String prefix:List.of("团队业绩\n","以下是他人的专业列表。\n","客户希望采购以下课程。\n","课程样例\n"))
                check(leading(prefix+source).isEmpty(),"unknown/other preface cannot be dropped");
            for(String suffix:List.of("\n以上不是本人专业方向。","\n上述列表属于团队能力。","\n这些勾选项来自课程模板。","\n本人角色：助教","\n该列表还待核实。"))
                check(leading(source+suffix).isEmpty(),"whole-source qualification remains bound "+suffix);
            check(leading(source.replace("✓ 分类标记整理","本人仅负责会务\n✓ 分类标记整理")).isEmpty(),"unknown line inside list not dropped");
            check(leading(source.replace("✓ 分类标记整理","• 分类标记整理")).isEmpty(),"mixed unestablished list marker");
            check(leading(source.replace("\n✓ 分类标记整理","\n\n✓ 分类标记整理")).isEmpty(),"paragraph gap cannot join separate lists");
            check(leading(source.replace("✓ "+topic+"\n","")).isEmpty(),"single item is not an independent list");
            check(leading(source.replace("✓ "+topic,"✓ 本人已主讲"+topic)).isEmpty(),"narrated teaching is not a content-list escape");
            String qualification=source.replace("✓ 分类标记整理","✓ 资格认证流程");
            check(leading(qualification).size()==1,"qualification noun is content, not possession");
            check(!admitted("培训主题："+topic+"；必须持有讲师资格证",qualification),"noun list cannot certify qualification");
            check(leading(source.replace("✓ 分类标记整理","✓ 已取得讲师资格")).isEmpty(),"credential assertion cannot hide in content list");
            check(leading("性别：女。年龄：52。\n"+source).size()==1,"personal-only metadata invariant");
            check(leading(source.replace("\n","\r\n")).size()==1,"CRLF boundaries preserved");
            check(leading(source.replace("精品课程","课程列表")).size()==1,"complete known course-list heading is not a discourse reference");
            check(leading(source+"\n上述列表不是本人方向。").isEmpty(),"actual backward list denial still binds");
            check(leading(source+"\n课程列表仅为团队产品。").isEmpty(),"a sentence is not erased as a heading");
            String unicode=source.replace(topic,topic+"📚");
            check(leading(unicode).size()==1,"literal non-BMP suffix retained");
            for(String old:List.of("independent_sections_v1","independent_facts_v2")) {Map<String,Object> stale=new LinkedHashMap<>(meta);stale.put("scoring_scope",old);check(!EvidenceSections.verified(source,stale),"old version cannot claim new kind");}
        }
        String longList=String.join("\n",Collections.nCopies(12,"✓ "+"标签整理".repeat(8)))+"\n个人介绍\n本人参加陶艺培训。";
        check(leading(longList).isEmpty(),"oversized list not cut to a prefix");
        check(leading("✓ 岩样编号核对\n✓ 分类标记整理").isEmpty(),"missing explicit closing boundary stays unknown");
        check(leading("✓ 岩样编号核对\n\n未承载说明\n个人介绍\n本人参加陶艺培训。").isEmpty(),"blank line is not permission to drop prose");
        for(String noun:List.of("自我调节方法","拟合误差检查","计划编排方法"))check(leading(text(noun)).size()==1,"noun substring is not an actor or future action "+noun);
        System.out.println("Leading profile scope: "+checks+" fixed assertions passed");
    }
}
