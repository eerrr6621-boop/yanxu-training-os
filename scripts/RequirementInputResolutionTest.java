package com.training;
import java.util.*;

/** New fixed development regression, not a replacement for the old input suite. */
public final class RequirementInputResolutionTest {
    static int checks;static List<String> failures=new ArrayList<>();
    static void check(boolean good,String label){checks++;if(!good)failures.add(label);}
    static final List<String> LABELS=List.of("客户单位","培训主题","参训对象","希望解决的问题","期望日期","预计课时","师资要求","补充说明");
    static Map<String,Object> fields(String topic,String key,String value){
        Map<String,Object> fields=new LinkedHashMap<>();for(String k:RequirementInput.FIELDS)fields.put(k,"");
        fields.put("topic","《"+topic+"》");fields.put(key,value);return fields;
    }
    static RequirementInput input(Map<String,Object> fields){
        List<String> lines=new ArrayList<>();for(int i=0;i<RequirementInput.FIELDS.size();i++){
            String s=Objects.toString(fields.get(RequirementInput.FIELDS.get(i)));if(!s.isEmpty())lines.add(LABELS.get(i)+"："+s);
        }
        String original=String.join("\n",lines);
        return RequirementInput.from(Map.of("requirement",original,"requirement_contract",Map.of("schema_version",RequirementInput.VERSION,"fields",fields)),original,91L);
    }
    static Map<String,Object> before(RequirementInput input,String text){
        Map<String,Object> c=new LinkedHashMap<>();c.put("_rule_relevant",true);c.put("professional_score",50.0);c.put("gaps",new ArrayList<String>());
        new RequirementCoverage(input.canonicalText).assess(c,text,false);return c;
    }
    static boolean admitted(Map<String,Object> c){return Boolean.TRUE.equals(c.get("_admitted"));}
    static boolean cleared(Map<String,Object> c,String key){return c.get("requirement_field_assessment") instanceof List<?> list&&list.stream().anyMatch(x->x instanceof Map<?,?> m&&("requirement_contract.fields."+key).equals(m.get("field_path"))&&Boolean.TRUE.equals(m.get("duplicate_gate_discharged")));}
    static Map<String,Object> assess(RequirementInput i,String text){Map<String,Object> c=before(i,text);i.annotate(c,text);return c;}
    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception{
        for(String[] domain:List.of(new String[]{"文档编号复核","见习文控员","资深文控员"},new String[]{"量具编号登记","新任检验员","资深检验员"}))for(String key:List.of("preference","extra")){
            String title=domain[0],aud=domain[1],other=domain[2],source="本人已经讲完《"+title+"》课程，培训对象："+aud+"。";
            for(String condition:List.of("《"+title+"》必须适配"+aud,"课程必须适合"+aud,"必须本人讲过《"+title+"》","必须本人讲完《"+title+"》的教学记录")){
                RequirementInput i=input(fields(title,key,condition));String original=Json.write(i.toMap());Map<String,Object> c=before(i,source);
                check(admitted(c),title+key+condition+": existing rules support complete condition");
                i.annotate(c,source);check(admitted(c),title+key+condition+": no duplicate gate");check(cleared(c,key),"receipt carries full recognized field");
                check(Json.write(i.toMap()).equals(original),"candidate result does not mutate shared input ledger");
                List<?> ledger=(List<?>)c.get("requirement_field_assessment");Map<?,?> receipt=(Map<?,?>)ledger.get(0);
                check(((Map<?,?>)receipt.get("requirement_field_source")).get("raw_value").equals(condition),"original field provenance retained");
                Map<String,Object> forced=before(i,source);forced.put("_admitted",false);forced.put("coverage_complete",false);i.annotate(forced,source);
                check(!admitted(forced)&&Boolean.FALSE.equals(forced.get("coverage_complete")),"annotate never promotes existing rejection");
            }
            String fit="《"+title+"》必须适配"+aud;
            RequirementInput i=input(fields(title,key,fit));Map<String,Object> wrong=assess(i,"本人已经讲完《"+title+"》课程，培训对象："+other+"。");
            check(!admitted(wrong)&&!cleared(wrong,key),"wrong audience cannot clear gate");
            Map<String,Object> tagFirst=assess(i,title+"\n"+source);
            check(admitted(tagFirst)&&cleared(tagFirst,key),"a profile tag selected first does not hide a second complete bound source unit");
            Map<String,Object> noFullSource=before(i,source);i.annotate(noFullSource);
            check(!admitted(noFullSource)&&!cleared(noFullSource,key),"legacy overload cannot assume a displayed quote is a complete source");
            Map<String,Object> stale=before(i,source);i.annotate(stale,title+"\n本人主讲《另一独立课程》。");
            check(!admitted(stale)&&!cleared(stale,key),"removed manual profile audience/history cannot be resurrected from a stale check");
            for(String denied:List.of(title+"\n同事主讲《"+title+"》，培训对象："+aud+"；本人仅签到。",title+"\n本人主讲《"+title+"》，明确不面向"+aud+"。")){
                Map<String,Object> badRole=assess(i,denied);check(!admitted(badRole)&&!cleared(badRole,key),"other actor or same-unit denied audience not borrowed");
            }
            Map<String,Object> borrowed=assess(i,"本人主讲《"+title+"》。\n本人主讲《另一独立课程》，培训对象："+aud+"。");
            check(!admitted(borrowed)&&!cleared(borrowed,key),"different course cannot lend audience");
            for(String tail:List.of("且持有资格证书","，必须提供尚未核实的推荐信","；2026年10月必须到场","。还需具备未知认证","、不得采用另一个未定义形式","；《另一独立课程》也必须适配"+aud)){
                RequirementInput bad=input(fields(title,key,fit+tail));Map<String,Object> c=assess(bad,source);
                check(!admitted(c)&&!cleared(c,key),"complete prefix does not consume unknown tail: "+tail);
                check(((List<?>)c.get("unresolved_guided_fields")).contains(key),"unknown field remains explicit");
            }
            Map<String,Object> f=fields(title,key,fit);f.put("topic","《"+title+"》与《另一独立课程》");
            Map<String,Object> multi=assess(input(f),source+"\n本人主讲《另一独立课程》，培训对象："+aud+"。");
            check(!cleared(multi,key),"multiple course field remains outside bounded discharge");
            RequirementInput original=input(fields(title,key,fit));Map<String,Object> forged=before(original,source);
            Map<String,Object> audit=new LinkedHashMap<>((Map<String,Object>)forged.get("requirement_topic_contract"));audit.put("source",original.canonicalText+"额外文字");forged.put("requirement_topic_contract",audit);original.annotate(forged,source);
            check(!cleared(forged,key)&&!admitted(forged),"mismatched canonical audit rejected");
            Map<String,Object> partial=before(original,source);for(Map<String,Object> check:(List<Map<String,Object>>)partial.get("requirement_coverage"))if(Objects.toString(check.get("criterion")).startsWith("授课对象："))check.put("evidence","另一条不相同的源记录");
            original.annotate(partial,source);check(!cleared(partial,key),"different source excerpt does not discharge same-course proof");
            Map<String,Object> unicode=fields(title,key,fit);unicode.put("unit","😀 资料中心");unicode.put("date","2026-10-02");
            Map<String,Object> uc=assess(input(unicode),source);check(admitted(uc)&&cleared(uc,key),"astral prefix and masked operational date preserve exact field coordinates");
        }
        Map<String,Object> mixed=fields("文档编号复核","preference","《文档编号复核》必须适配见习文控员");mixed.put("extra","必须满足另一未定义条件");
        Map<String,Object> result=assess(input(mixed),"本人主讲《文档编号复核》，培训对象：见习文控员。");
        check(cleared(result,"preference")&&!cleared(result,"extra")&&!admitted(result),"candidate-local resolution preserves another unresolved field");
        java.lang.reflect.Method scope=RequirementInput.class.getDeclaredMethod("fullSourceProof",Map.class,Map.class,String.class);scope.setAccessible(true);
        String head="本人主讲《文档编号复核》课程，教学材料编号";
        for(int length:List.of(219,220,221)){
            String full=head+"甲".repeat(length-head.length()-1)+"。";
            String normalized=ProfessionalEvidence.normalized(full);
            String displayed=normalized.substring(0,Math.min(220,normalized.length()));
            Object found=scope.invoke(null,Map.of("evidence",displayed),Map.of(),full);
            check((found!=null)==(length<=220),"full source equality, not <220 assumption: "+length);
        }
        String elided="本人主讲《文档编号复核》课程……其余省略。";
        check(scope.invoke(null,Map.of("evidence",elided),Map.of(),elided)==null,"literal ellipsis is conservatively not complete proof");
        String duplicated="本人主讲《文档编号复核》课程。";
        check(scope.invoke(null,Map.of("evidence",duplicated),Map.of(),duplicated+"\n"+duplicated)==null,"identical source quotes at multiple positions remain ambiguous");
        for(String title:List.of("文档编号复核","量具编号登记")){
            String event="本人已独立面向新员工讲授过"+title+"。";
            String scopedSource=ProfessionalEvidence.normalized("✓ "+title+"\n✓ 分类标记整理\n个人介绍\n本人参加过陶艺课程培训。\n精品课程\n《课程资料归档》\n\n实际授课记录\n\n"+event);
            List<Map<String,Object>> scopes=new ArrayList<>(EvidenceSections.sections(scopedSource));scopes.addAll(TeachingEvents.scoringSections(scopedSource));
            Map<String,Object> metadata=new LinkedHashMap<>(Map.of("evidence_complete",false,"scoped_evidence_complete",true,"scoring_scope",EvidenceSections.MIXED_VERSION,"scoring_sections",scopes));
            check(EvidenceSections.verified(scopedSource,metadata),"mixed fixture has independently verified original source scopes");
            check(scope.invoke(null,Map.of("evidence",event),Map.of("semantic",metadata),scopedSource)!=null,"actual whole event in verified mixed scope may prove a complete condition");
            metadata.put("scoring_scope","unknown_version");
            check(scope.invoke(null,Map.of("evidence",event),Map.of("semantic",metadata),scopedSource)==null,"same literal quote cannot bypass invalid scope metadata");
            metadata.put("scoring_scope",EvidenceSections.MIXED_VERSION);
            check(scope.invoke(null,Map.of("evidence",event),Map.of("semantic",metadata),scopedSource+"\n以上不是本人授课经历。")==null,"full-source denial invalidates an otherwise identical scoped proof");
        }
        System.out.println(Json.write(Map.of("suite","guided-field-resolution","checks",checks,"failures",failures,"model_runs",0)));
        if(!failures.isEmpty())throw new AssertionError(failures.size()+" failed");
    }
}
