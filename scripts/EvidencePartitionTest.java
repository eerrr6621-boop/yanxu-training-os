package com.training;

import java.util.*;

/** Fresh fixed synthetic section/protocol diagnostics, not model accuracy. */
public final class EvidencePartitionTest {
    static int passed;
    static void check(boolean ok,String label) { if(!ok) throw new AssertionError(label);passed++; }
    static Map<String,Object> metadata(String source) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("similarity",0.75);result.put("evidence_complete",false);
        result.put("scoped_evidence_complete",true);result.put("scoring_scope",EvidenceSections.VERSION);
        result.put("scoring_sections",EvidenceSections.sections(source));return result;
    }
    static Map<String,Object> assess(String query,String source) {
        Map<String,Object> c=new LinkedHashMap<>();c.put("professional_score",80.0);
        c.put("_rule_relevant",true);c.put("gaps",new ArrayList<String>());c.put("semantic",metadata(source));
        new RequirementCoverage(query).assess(c,source,true);return c;
    }
    static boolean admitted(String query,String source) { return Boolean.TRUE.equals(assess(query,source).get("_admitted")); }
    static Map<String,Object> response(String source,String quote) {
        // Production sends NFKC normalized documents to the worker.
        Map<String,Object> row=metadata(source);row.put("id",1L);row.put("evidence",List.of(LocalSemantic.normalized(quote)));
        return new LinkedHashMap<>(Map.of("complete",true,"status","ready","model",LocalSemantic.MODEL,
            "revision",LocalSemantic.REVISION,"precision","int8","results",List.of(row)));
    }
    static boolean validates(String source,Map<String,Object> response) {
        try {LocalSemantic.validate(response,Map.of(1L,LocalSemantic.normalized(source)));return true;}
        catch(IllegalArgumentException expected) {return false;}
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        String prefix="个人介绍\n本人参加摄影课程培训。\n";
        String record="2025年本人主讲服务礼仪课程，实际授课120分钟。";
        String source=prefix+"实际授课记录\n"+record;
        check(admitted("培训主题：服务礼仪",source),"Independent teaching is not invalidated by attendance elsewhere");
        check(admitted("培训主题：服务礼仪；至少120分钟",source),"Independent exact teaching duration retained");
        check(!admitted("培训主题：服务礼仪；至少180分钟",source),"Scoped score does not upgrade duration");
        check(!admitted("培训主题：服务礼仪；必须用粤语授课",source),"Unknown declared language still requires evidence");
        check(validates(source,response(source,record)),"Production validator accepts original scoped quote without complete-document fiction");
        check(!Boolean.TRUE.equals(LocalSemantic.validate(response(source,record),Map.of(1L,LocalSemantic.normalized(source))).get(1L).get("evidence_complete")),"Whole-document incomplete flag preserved");
        check(admitted("培训主题：服务礼仪",prefix+"精品课程\n服务礼仪课程"),"Course catalog may support topic relevance");
        check(!admitted("培训主题：服务礼仪；必须有实际授课记录",prefix+"精品课程\n服务礼仪课程"),"Catalog alone is not completed teaching history");
        check(!admitted("培训主题：摄影；必须有实际授课记录",source),"Excluded attendance cannot be reused for qualification");
        check(!admitted("培训主题：服务礼仪；至少180分钟",prefix+"工作经历\n本人主讲服务礼仪课程180分钟。\n实际授课记录\n本人主讲服务礼仪课程60分钟。"),"Out-of-scope number cannot patch insufficient selected section");
        check(!admitted("培训主题：服务礼仪；必须有实际授课记录",prefix+"团队业绩\n2025年主讲服务礼仪课程。\n精品课程\n服务礼仪课程"),"Team record not used as personal history");
        check(!admitted("培训主题：服务礼仪；必须有实际授课记录",prefix+"实际授课记录\n外部讲师主讲服务礼仪，本人负责签到。"),"Other actor under positive heading still excluded");
        check(!admitted("培训主题：服务礼仪；必须有实际授课记录",prefix+"实际授课记录\n本人拟主讲服务礼仪。"),"Future teaching still not completed history");
        for(String denial:List.of("上述课程均未由本人主讲。","以上内容只是团队案例。","这些记录非本人授课。","前页授课记录待确认。")) {
            String contradicted=source+"\n\n个人介绍\n"+denial;
            check(!admitted("培训主题：服务礼仪",contradicted),"Backward disclaimer survives section boundary");
        }
        check(admitted("培训主题：服务礼仪",prefix+"精品课程\n服务礼仪课程\n替换个人照片"),"Standalone layout placeholder does not erase course topic");
        check(!admitted("培训主题：服务礼仪；必须有实际授课记录",prefix+"实际授课记录\n2025年xxx主讲服务礼仪课程。"),"Placeholder actor never becomes personal proof");
        check(admitted("培训主题：服务礼仪","性别：女。年龄：52。\n"+source),"Personal metadata invariance");
        Map<String,Object> forged=response(source,record);
        Map<String,Object> row=(Map<String,Object>)((List<?>)forged.get("results")).get(0);
        row.put("scoring_sections",List.of(Map.of("heading","实际授课记录","heading_start",0,"heading_end",6,
            "source_start",0,"source_end",source.length(),"offset_unit","unicode_code_point")));
        check(!validates(source,forged),"Forged whole-document range rejected");
        check(!validates(source,response(source,"伪造的授课证明")),"Fabricated quote rejected");
        check(!validates(source,response(source,"本人参加摄影课程培训。")),"Authentic but out-of-scope quote rejected");
        String unicode="🧪\n"+source;
        check(validates(unicode,response(unicode,record)),"Unicode code-point ranges are not UTF-16 offsets");
        String masked=EvidenceSections.maskedSource(source,metadata(source));
        check(masked.indexOf(LocalSemantic.normalized(record))==source.indexOf(record),"Mask preserves original source positions");
        check(!masked.contains("摄影"),"Mask removes excluded source rather than relabeling it");
        String conflict="未授课课程\n服务礼仪课程\n"+source;
        check(!admitted("培训主题：服务礼仪；必须有实际授课记录",conflict),"Categorical denial of same topic survives scoped masking");
        check(admitted("培训主题：服务礼仪","未授课课程\n财税实务\n"+source),"Clearly different denied topic does not invalidate independent topic evidence");
        check(!admitted("培训主题：机械识图","未授课课程\n机械识图\n"+prefix+"实际授课记录\n2025年本人主讲机械识图。"),"Unknown-topic denial is not silently aligned away");
        check(!admitted("培训主题：服务礼仪","个人介绍\n以下内容只是团队案例。\n实际授课记录\n2025年本人主讲服务礼仪。"),"Forward disclaimer survives heading boundary");
        System.out.println("Evidence partition: "+passed+" checks passed; synthetic protocol only, no models invoked");
    }
}
