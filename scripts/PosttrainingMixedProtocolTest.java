package com.training;

import java.lang.reflect.*;
import java.util.*;

/** Literal synthetic protocol fixtures, not model responses or accuracy tests.
 * --observe-baseline preserves the old probe's outcomes without relabelling. */
public final class PosttrainingMixedProtocolTest {
    private static final String QUERY="培训主题：装订页序复核";
    private static int checks;
    private static final List<Map<String,Object>> observations=new ArrayList<>();
    private static final List<String> failures=new ArrayList<>();
    private static boolean observe;
    private static void check(boolean condition,String message){checks++;if(!condition)failures.add(message);}
    @SuppressWarnings("unchecked") private static Map<String,Object> copy(Map<String,Object> value){return (Map<String,Object>)Json.parse(Json.write(value));}
    private static Map<String,Object> metadata(String source){
        List<Map<String,Object>> scopes=new ArrayList<>(EvidenceSections.sections(source));
        scopes.addAll(TeachingEvents.scoringSections(source));
        return new LinkedHashMap<>(Map.of("evidence_complete",false,"scoped_evidence_complete",true,
            "scoring_scope",EvidenceSections.MIXED_VERSION,"scoring_sections",scopes,
            "id",1,"similarity",0.5,"evidence_review_reasons",List.of("mixed_personal_fields")));
    }
    private static Map<String,Object> unit(String source,Map<String,Object> scope){
        int start=((Number)scope.get("source_start")).intValue(),end=((Number)scope.get("source_end")).intValue();
        boolean event=TeachingEvents.SCORING_SCOPE.equals(scope.get("kind"));
        Map<String,Object> unit=new LinkedHashMap<>(Map.of("text",source.substring(source.offsetByCodePoints(0,start),source.offsetByCodePoints(0,end)),
            "source_start",start,"source_end",end,"paragraph_start",scope.getOrDefault("context_start",start),
            "paragraph_end",scope.getOrDefault("context_end",end),"offset_unit","unicode_code_point","kind","retrieval_only",
            "context_status",event?"server_source_event":"independent_fact_after_section_review"));
        unit.put("section_heading",event?null:scope.get("heading"));return unit;
    }
    private static Map<String,Object> row(Map<String,Object> metadata,Map<String,Object> unit){
        Map<String,Object> row=copy(metadata);row.put("evidence",List.of(unit.get("text")));row.put("evidence_units",List.of(unit));return row;
    }
    private static Map<String,Object> response(Map<String,Object> row){
        Map<String,Object> result=new LinkedHashMap<>(Map.of("complete",true,"status","ready","experimental_evaluation",true,
            "model",LocalSemantic.MODEL,"revision",LocalSemantic.REVISION,"precision","int8","results",List.of(row)));
        result.put("requirement_analysis",Map.of("positive_fragments",List.of(QUERY),"exclusions",List.of(),
            "requires_review",false,"exclusions_enforced",false,"review_reasons",List.of()));return result;
    }
    private static String probe(Map<String,Object> response,String source)throws Exception{
        Class<?> identity=Class.forName("com.training.PosttrainingV2Probe$Identity");
        Constructor<?> ctor=identity.getDeclaredConstructor(String.class,String.class,String.class);ctor.setAccessible(true);
        Method validate=PosttrainingV2Probe.class.getDeclaredMethod("validate",Map.class,Map.class,String.class,identity);validate.setAccessible(true);
        try{validate.invoke(null,response,Map.of(1L,source),QUERY,ctor.newInstance(LocalSemantic.MODEL,LocalSemantic.REVISION,"int8"));return "accepted";}
        catch(InvocationTargetException e){if(e.getCause() instanceof IllegalArgumentException)return e.getCause().getMessage();throw e;}
    }
    private static void trial(String name,String source,Map<String,Object> row,boolean expected)throws Exception{
        Map<String,Object> response=response(row);boolean production;
        try{LocalSemantic.validate(response,Map.of(1L,source));production=true;}catch(IllegalArgumentException e){production=false;}
        String actual=probe(response,source);boolean accepted="accepted".equals(actual);
        // Both transports are tested; no HTTP, embeddings, score derivation or eligibility assertion.
        check(production==expected,name+" production contract");
        if(!observe){check(accepted==expected,name+" probe contract");check(accepted==production,name+" same transport decision");}
        observations.add(Map.of("name",name,"source",source,"response",response,"expected_transport_acceptance",expected,
            "production_transport_accepted",production,"probe_result",actual));
    }
    @SuppressWarnings("unchecked") public static void main(String[] args)throws Exception{
        observe=args.length==1&&"--observe-baseline".equals(args[0]);
        if(args.length>0&&!observe)throw new IllegalArgumentException("only --observe-baseline is supported");
        for(String[] domain:List.of(new String[]{"装订页序复核","纸册装盒"},new String[]{"帆索标记整理","绳端收纳"},new String[]{"窑具格号登记","泥坯摆放"})){
            String topic=domain[0],catalog=domain[1];
            String source=LocalSemantic.normalized("✓ "+topic+"\n✓ 编号检查\n个人介绍\n本人参加过《"+catalog+"》课程培训。\n精品课程\n《"+catalog+"》\n\n实际授课记录\n\n本人已独立面向新员工讲授过"+topic+"。");
            Map<String,Object> meta=metadata(source);check(EvidenceSections.verified(source,meta),topic+" real mixed scopes");
            List<Map<String,Object>> scopes=(List<Map<String,Object>>)meta.get("scoring_sections");
            Map<String,Object> profile=scopes.stream().filter(s->"leading_profile_list_v1".equals(s.get("kind"))).findFirst().orElseThrow();
            Map<String,Object> event=scopes.stream().filter(s->TeachingEvents.SCORING_SCOPE.equals(s.get("kind"))).findFirst().orElseThrow();
            Map<String,Object> course=scopes.stream().filter(s->"精品课程".equals(s.get("heading"))).findFirst().orElseThrow();
            Map<String,Object> p=unit(source,profile),e=unit(source,event),c=unit(source,course);
            trial(topic+" empty heading",source,row(meta,p),true);
            trial(topic+" event null heading",source,row(meta,e),true);
            trial(topic+" catalog heading",source,row(meta,c),true);
            Map<String,Object> changed=copy(c);changed.put("section_heading","个人介绍");
            trial(topic+" unrelated real heading",source,row(meta,changed),false);
            // A reused paragraph unit represents a truly headingless scope with
            // null, while a newly built unit uses "". Both refer to this range.
            changed=copy(p);changed.put("section_heading",null);trial(topic+" actual headingless profile null",source,row(meta,changed),true);
            changed=copy(c);changed.put("section_heading",null);trial(topic+" nonempty catalog cannot use null",source,row(meta,changed),false);
            changed=copy(c);changed.put("section_heading","");trial(topic+" catalog cannot borrow profile empty",source,row(meta,changed),false);
            changed=copy(e);changed.put("section_heading","");trial(topic+" actual empty event scope heading",source,row(meta,changed),true);
            changed=copy(c);String duplicate="《"+catalog+"》";int at=source.indexOf(duplicate);int cp=source.codePointCount(0,at);
            changed.put("text",duplicate);changed.put("source_start",cp);changed.put("source_end",cp+duplicate.codePointCount(0,duplicate.length()));
            changed.put("paragraph_start",cp);changed.put("paragraph_end",cp+duplicate.codePointCount(0,duplicate.length()));changed.put("section_heading",null);
            check(EvidenceSections.containsQuote(source,meta,duplicate),topic+" quote text exists in approved other occurrence");
            trial(topic+" excluded duplicate occurrence",source,row(meta,changed),false);
            changed=copy(e);changed.put("source_start",0);trial(topic+" offset mismatch",source,row(meta,changed),false);
            changed=copy(e);changed.remove("section_heading");trial(topic+" missing heading",source,row(meta,changed),false);
            changed=copy(e);changed.put("section_heading",7);trial(topic+" numeric heading",source,row(meta,changed),false);
            for(String version:List.of("independent_profiles_v1","independent_facts_v2","source_teaching_events_v1","future_unknown_v1")){
                Map<String,Object> old=copy(meta);old.put("scoring_scope",version);trial(topic+" wrong version "+version,source,row(old,e),false);
            }
            String postposed=LocalSemantic.normalized("教育背景\n本人参加过陶艺课程培训。\n个人介绍\n擅长资料整理。\n《"+catalog+"》\n《样本分类》\n精品课程\n《文档编号》\n\n实际授课记录\n\n本人已独立面向新员工讲授过"+topic+"。");
            Map<String,Object> postmeta=metadata(postposed);check(EvidenceSections.verified(postposed,postmeta),topic+" actual postposed scopes");
            Map<String,Object> post=((List<Map<String,Object>>)postmeta.get("scoring_sections")).stream()
                .filter(s->"course_catalog_entry_v2".equals(s.get("kind"))&&((Number)s.get("source_start")).intValue()<postposed.indexOf("精品课程")).findFirst().orElseThrow();
            trial(topic+" postposed catalog heading",postposed,row(postmeta,unit(postposed,post)),true);
            String unicode=source.replace("本人参加过","📚本人参加过");Map<String,Object> unicodeMeta=metadata(unicode);
            // Use a true event scope after a supplementary code point, not UTF-16 offsets.
            Map<String,Object> unicodeEvent=TeachingEvents.scoringSections(unicode).get(0);
            check(EvidenceSections.verified(unicode,unicodeMeta),topic+" unicode scopes");
            trial(topic+" unicode literal event",unicode,row(unicodeMeta,unit(unicode,unicodeEvent)),true);
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("mode",observe?"baseline_observation":"current_contract");
        result.put("checks",checks);result.put("failures",failures);result.put("model_runs",0);
        result.put("notice","Fictional protocol scores; no model, HTTP, real data, eligibility or accuracy measurement.");result.put("observations",observations);
        System.out.println(Json.write(result));if(!failures.isEmpty())throw new AssertionError(failures.size()+" protocol failures");
    }
}
