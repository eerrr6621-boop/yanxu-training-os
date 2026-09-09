package com.training;

import java.util.*;
import java.nio.charset.StandardCharsets;

/** New synthetic source/protocol tests. No models, database or historical labels. */
public final class EvidenceFactsTest {
    static int passed;
    static void check(boolean ok,String label) { if(!ok) throw new AssertionError(label);passed++; }
    static Map<String,Object> metadata(String source) {
        List<Map<String,Object>> sections=EvidenceSections.sections(source);
        return new LinkedHashMap<>(Map.of("evidence_complete",false,"scoped_evidence_complete",true,
            "scoring_scope",EvidenceSections.versionFor(sections),"scoring_sections",sections));
    }
    static boolean history(String source) {
        return ProfessionalEvidence.records(source).stream().anyMatch(r->"teaching_history_statement".equals(r.get("role")));
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if(args.length==1 && args[0].equals("--validate-scopes")) {
            Map<String,Object> request=(Map<String,Object>)Json.parse(new String(System.in.readAllBytes(),StandardCharsets.UTF_8));
            List<Map<String,Object>> results=new ArrayList<>();
            for(Object raw:(List<?>)request.get("documents")) {
                Map<String,Object> row=(Map<String,Object>)raw;String source=(String)row.get("text");
                Map<String,Object> details=(Map<String,Object>)row.get("details");
                boolean scoped=Boolean.TRUE.equals(details.get("scoped_evidence_complete"));
                boolean valid=!scoped || EvidenceSections.verified(source,details);
                if(scoped) for(Object quote:(List<?>)details.get("evidence")) valid &= EvidenceSections.containsQuote(source,details,(String)quote);
                results.add(Map.of("id",row.get("id"),"valid",valid,"scoped",scoped));
            }
            System.out.println(Json.write(Map.of("results",results,"contains_source_text",false)));
            if(results.stream().anyMatch(r->!Boolean.TRUE.equals(r.get("valid")))) throw new AssertionError("Python/Java scope disagreement");
            return;
        }
        String fact=LocalSemantic.normalized("本人曾面向社区长者主讲网络安全课程，说明防范陌生链接的方法。");
        String source=LocalSemantic.normalized("个人介绍\n聘任岗位（筹）。\n"+fact+"\n精品课程\n网络安全课程");
        Map<String,Object> meta=metadata(source);
        check(EvidenceSections.FACT_VERSION.equals(meta.get("scoring_scope")),"New independent fact uses version 2");
        check(EvidenceSections.verified(source,meta),"Complete original context recomputes exactly");
        String masked=EvidenceSections.maskedSource(source,meta);
        check(masked.contains(fact),"Independent introductory fact retained");
        check(!masked.contains("聘任岗位"),"Intervening unconfirmed introduction not restored");
        check(masked.indexOf(fact)==source.indexOf(fact),"Mask preserves source offsets");
        check(EvidenceSections.containsQuote(source,meta,fact),"Exact original fact quote validated");
        Map<String,Object> old=new LinkedHashMap<>(meta);old.put("scoring_scope",EvidenceSections.VERSION);
        check(!EvidenceSections.verified(source,old),"Version 1 cannot claim new fact scope");
        Map<String,Object> unknown=new LinkedHashMap<>(meta);unknown.put("scoring_scope","future_unknown");
        check(!EvidenceSections.verified(source,unknown),"Unknown scope version rejected");
        List<Map<String,Object>> forged=new ArrayList<>();
        for(Map<String,Object> s:(List<Map<String,Object>>)meta.get("scoring_sections")) forged.add(new LinkedHashMap<>(s));
        Map<String,Object> changed=forged.stream().filter(s->s.containsKey("kind")).findFirst().orElseThrow();
        changed.put("context_start",changed.get("source_start"));
        Map<String,Object> forgery=new LinkedHashMap<>(meta);forgery.put("scoring_sections",forged);
        check(!EvidenceSections.verified(source,forgery),"Cut-down context range cannot hide qualifier");
        check(!EvidenceSections.containsQuote(source,meta,"聘任岗位（筹）。"),"Authentic out-of-scope quote rejected");
        check(!EvidenceSections.containsQuote(source,meta,"本人已取得全部资质"),"Invented quote rejected");

        String titles="个人介绍\n模板岗位待补充。\n《仓库盘点与差异分析》\n《机械识图——尺寸与公差》\n精品课程\n质量管理";
        Map<String,Object> catalog=metadata(titles);
        check(EvidenceSections.verified(titles,catalog),"Adjacent backward catalog run is independently verified");
        check(EvidenceSections.containsQuote(titles,catalog,"《机械识图——尺寸与公差》"),"Backward title retains exact punctuation");
        check(EvidenceSections.maskedSource(titles,catalog).contains("《仓库盘点与差异分析》"),"Heading after title does not erase title body");
        check(!EvidenceSections.maskedSource(titles,catalog).contains("模板岗位"),"Backward mask never spans all text up to heading");
        check(EvidenceSections.sections("个人介绍\n《仓库盘点》\n精品课程").isEmpty(),"Single unowned title remains ambiguous");
        check(EvidenceSections.sections("个人介绍\n《仓库盘点》\n《质量管理》\n\n精品课程").isEmpty(),"Page gap does not imply catalog ownership");
        for(String risk:List.of("他人讲过以下课程。","王老师曾主讲仓库盘点。","以下是计划课程。","课程清单尚待确认。","本人只参加以下课程培训。")) {
            String unsafe="个人介绍\n"+risk+"\n《仓库盘点》\n《质量管理》\n精品课程";
            check(EvidenceSections.sections(unsafe).isEmpty(),"Unconfirmed or other-actor titles not restored");
        }
        String field="个人介绍\n本人参加摄影课程培训。\n擅长领域\n《仓库盘点》\n《质量管理》\n精品课程\n采购管理";
        check(EvidenceSections.containsQuote(field,metadata(field),"《仓库盘点》"),"Explicit positive specialty heading owns its course list");
        check(EvidenceSections.sections("个人介绍\n以下是计划课程。\n擅长领域\n《仓库盘点》\n精品课程\n质量管理").isEmpty(),"Forward plan cannot be reset by specialty heading");

        String mixed=LocalSemantic.normalized("本人已取得食品安全认证，曾面向餐饮店长开展食品安全课堂。");
        List<Map<String,Object>> records=ProfessionalEvidence.records(mixed);
        check(history(mixed),"Completed teaching survives separate certification fact on same line");
        Map<String,Object> event=records.stream().filter(r->"teaching_history_statement".equals(r.get("role"))).findFirst().orElseThrow();
        check(!mixed.equals(event.get("text")),"Whole certification line is not relabelled as teaching");
        check(mixed.equals(event.get("context_text")),"Full subject/qualification context retained");
        int at=(Integer)event.get("source_offset");String quote=(String)event.get("text");
        check(mixed.substring(at,at+quote.length()).equals(quote),"Event substring is exactly contiguous original evidence");
        check(Boolean.FALSE.equals(event.get("verified")),"Self-report does not become certified qualification");
        check(!history("本人取得食品安全认证。"),"Qualification alone is not completed teaching");
        check(!history("本人取得食品安全认证，拟面向餐饮店长开展食品安全课堂。"),"Planned event never becomes completed teaching");
        check(!history("本人取得食品安全认证，其他老师曾主讲食品安全课程。"),"Other teacher event not inherited from owner certificate");
        check(!history("本人参加食品安全认证培训，已完成培训结业。"),"Certification attendance not completed personal teaching");
        check(!history("本机构已取得食品安全认证，曾开展食品安全课堂。"),"Organization certification cannot lend actor to later event");
        check(!history("团队已取得食品安全认证，曾开展食品安全课堂。"),"Team completed event does not become individual teaching");
        check(history("授课风采\n某社区食品安全授课"),"Unambiguous teaching event caption recognizes 授课");
        check(!history("授课风采\n某社区食品安全授课讲师资格"),"Qualification caption not event history");
        check(!history("授课风采\n拟开展某社区食品安全授课"),"Future event caption stays uncompleted");
        check(!history("授课风采\n外部讲师完成某社区食品安全授课"),"Other teacher caption stays unowned");
        check(history("该讲师已为餐饮店长完成两次食品安全工作坊。"),"Declared instructor completed workshops are personal event records");
        check(!history("该讲师已协助餐饮店长完成两次食品安全工作坊。"),"Assistance does not borrow completed workshop role");
        String unicode="🧪\n"+source;
        check(EvidenceSections.verified(unicode,metadata(unicode)),"Unicode offsets recompute as code points");
        check(EvidenceSections.maskedSource("年龄：48。性别：男。\n"+source,metadata("年龄：48。性别：男。\n"+source)).contains(fact),"Personal metadata invariance");
        check(EvidenceSections.sections(source+"\n本人没有承担上述课程教学。").isEmpty(),"Later denial overrides recovered earlier facts");
        System.out.println("Evidence facts: "+passed+" checks passed; synthetic source/protocol only");
    }
}
