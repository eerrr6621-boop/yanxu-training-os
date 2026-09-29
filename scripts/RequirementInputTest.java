package com.training;

import java.util.*;

/** Independent guided contract/provenance tests; no training or database. */
public final class RequirementInputTest {
    static int checks;
    static final List<String> LABELS=List.of("客户单位","培训主题","参训对象","希望解决的问题","期望日期","预计课时","师资要求","补充说明");
    static void check(boolean value,String label) { if(!value) throw new AssertionError(label);checks++; }
    static Map<String,Object> fields() {
        Map<String,Object> fields=new LinkedHashMap<>();for(String key:RequirementInput.FIELDS) fields.put(key,"");
        fields.put("topic","文档编号复核");fields.put("audience","见习文控员");return fields;
    }
    static Map<String,Object> body(Map<String,Object> fields) {
        List<String> text=new ArrayList<>();
        for(int i=0;i<RequirementInput.FIELDS.size();i++) {
            Object raw=fields.get(RequirementInput.FIELDS.get(i));
            if(raw instanceof String value && !value.strip().isEmpty()) text.add(LABELS.get(i)+"："+value.strip());
        }
        return new LinkedHashMap<>(Map.of("requirement",String.join("\n",text),"requirement_contract",new LinkedHashMap<>(Map.of("schema_version",RequirementInput.VERSION,"fields",fields))));
    }
    static RequirementInput input(Map<String,Object> body) { return RequirementInput.from(body,"旧主题不得追加",7L); }
    static Map<String,Object> assess(RequirementInput input,String source) {
        Map<String,Object> candidate=new LinkedHashMap<>();candidate.put("_rule_relevant",true);candidate.put("professional_score",50.0);candidate.put("gaps",new ArrayList<String>());
        candidate.put("semantic",Map.of("similarity",.999,"evidence_complete",true));
        new RequirementCoverage(input.canonicalText).assess(candidate,source,true);input.annotate(candidate);return candidate;
    }
    static void reject(Map<String,Object> body,String label) {
        boolean failed=false;try { input(body); } catch(IllegalArgumentException expected) { failed=true; }check(failed,label);
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        for(String[] pair:List.of(new String[]{"文档编号复核","见习文控员","资深文控员"},new String[]{"量具编号登记","新任检验员","资深检验员"})) {
            Map<String,Object> fields=fields();fields.put("topic",pair[0]);fields.put("audience",pair[1]);
            Map<String,Object> body=body(fields);RequirementInput input=input(body);
            check(input.canonicalText.contains("培训对象："+pair[1])&&!input.canonicalText.contains("参训对象："),"single canonical audience label");
            check(input.originalText.equals(body.get("requirement")),"renderer original preserved");
            check(!input.canonicalText.contains("旧主题"),"current guided contract does not concatenate old demand");
            check(Objects.equals(input.toMap().get("source_demand_id"),7L),"linked demand provenance preserved");
            Map<String,Object> positive=assess(input,"本人主讲"+pair[0]+"课程，培训对象："+pair[1]+"。");
            check(Boolean.TRUE.equals(positive.get("_admitted")),"true audience admitted");
            Map<String,Object> different=assess(input,"本人主讲"+pair[0]+"课程，培训对象："+pair[2]+"。");
            check(Boolean.TRUE.equals(different.get("_admitted")),"different past audience does not invalidate a supported topic for future training");
            Map<String,Object> otherCourse=assess(input,"本人主讲"+pair[0]+"课程。\n本人主讲其他独立课程，培训对象："+pair[1]+"。");
            check(Boolean.TRUE.equals(otherCourse.get("_admitted")),"future audience remains non-hard context");
            check(((List<Map<String,Object>>)otherCourse.get("audience_context")).stream().noneMatch(v->Boolean.TRUE.equals(v.get("source_audience_type_supported"))),"different course cannot lend audience evidence even to non-hard audit");
            for(Map<String,Object> criterion:(List<Map<String,Object>>)positive.get("requirement_coverage")) {
                if(criterion.get("requirement_source_span") instanceof Map<?,?> span) {
                    check(span.get("source").equals("requirement_canonical"),"canonical source never called original");
                    int left=input.canonicalText.offsetByCodePoints(0,((Number)span.get("source_start")).intValue());
                    int right=input.canonicalText.offsetByCodePoints(0,((Number)span.get("source_end")).intValue());
                    check(input.canonicalText.substring(left,right).equals(span.get("text")),"canonical quote exact");
                }
            }
            List<Map<String,Object>> contexts=(List<Map<String,Object>>)positive.get("audience_context");
            check(contexts.size()==1,"exactly one guided future audience audit");
            for(Map<String,Object> context:contexts) {
                Map<?,?> origin=(Map<?,?>)context.get("requirement_field_source");
                check(origin.get("field_path").equals("requirement_contract.fields.audience")&&origin.get("raw_value").equals(pair[1]),"audience actual field source retained outside hard checks");
                Map<?,?> span=(Map<?,?>)context.get("requirement_source_span");
                check(span.get("source").equals("requirement_canonical"),"context span identifies actual canonical source");
                int left=input.canonicalText.offsetByCodePoints(0,((Number)span.get("source_start")).intValue());
                int right=input.canonicalText.offsetByCodePoints(0,((Number)span.get("source_end")).intValue());
                check(input.canonicalText.substring(left,right).equals(span.get("text")),"non-hard context canonical quote exact");
                check(Boolean.FALSE.equals(context.get("hard_requirement"))&&!context.containsKey("status"),"context provenance must not introduce a hard-check status");
            }
        }
        Map<String,Object> goal=fields();goal.put("goals","帮助学员掌握编号管理");RequirementInput goals=input(body(goal));
        check(!new RequirementConstraints(goals.canonicalText).teachingHistory,"training goal not past instructor experience");
        check(Boolean.TRUE.equals(assess(goals,"本人主讲文档编号复核课程，培训对象：见习文控员。").get("_admitted")),"ordinary guided goals do not reject every positive");
        for(String key:List.of("preference","extra","goals","unit")) {
            Map<String,Object> fields=fields();fields.put(key,"必须满足尚未结构化的课堂产出要求");RequirementInput input=input(body(fields));
            check(Boolean.TRUE.equals(input.toMap().get("requires_input_review")),"explicit incomplete field marked pending "+key);
            check(!Boolean.TRUE.equals(assess(input,"本人主讲文档编号复核课程，培训对象：见习文控员。").get("_admitted")),"high score cannot bypass incomplete field "+key);
        }
        for(String activityGoal:List.of("课堂让学员制作独立成果","学员当堂完成指定任务","本人已独立讲完对应课程")) {
            Map<String,Object> fields=fields();fields.put("goals",activityGoal);RequirementInput input=input(body(fields));
            check(Boolean.TRUE.equals(input.toMap().get("requires_input_review")),"explicit actor/task without 必须 remains pending");
            check(!Boolean.TRUE.equals(assess(input,"本人主讲文档编号复核课程，培训对象：见习文控员。").get("_admitted")),"goal cannot silently stand in for missing task/history");
        }
        Map<String,Object> unicode=fields();unicode.put("unit","😀");unicode.put("topic","  ＡＢＣ文档编号  ");
        RequirementInput un=input(body(unicode));
        check(un.canonicalText.contains("ABC文档编号")&&un.originalText.contains("ＡＢＣ文档编号"),"normalization does not rewrite original");
        List<Map<String,Object>> ledger=(List<Map<String,Object>>)un.toMap().get("field_ledger");
        check(ledger.size()==8,"all fields represented including empty");
        for(Map<String,Object> entry:ledger) if(entry.get("original_span") instanceof Map<?,?> span) {
            int start=un.originalText.offsetByCodePoints(0,((Number)span.get("source_start")).intValue());
            int end=un.originalText.offsetByCodePoints(0,((Number)span.get("source_end")).intValue());
            check(un.originalText.substring(start,end).equals(span.get("text")),"original Unicode point span");
        }
        Map<String,Object> metadata=fields();metadata.put("date","2026-10-02");metadata.put("hours","6.5");RequirementInput planned=input(body(metadata));
        check(planned.date().equals("2026-10-02")&&planned.hours()==6.5,"typed requested schedule");
        check(new RequirementConstraints(planned.canonicalText).minimums.isEmpty(),"planned course hours not teaching history duration");
        for(Object invalid:List.of(12,List.of("x"),Map.of("value","x"))) { Map<String,Object> fields=fields();fields.put("audience",invalid);reject(body(fields),"field wrong type rejected"); }
        Map<String,Object> nullValue=fields();nullValue.put("audience",null);reject(body(nullValue),"null field rejected");
        for(String key:List.of("industry","unknown")) {Map<String,Object> fields=fields();fields.put(key,"不能静默丢弃");reject(body(fields),"unknown nonempty field rejected");}
        Map<String,Object> missing=fields();missing.remove("extra");reject(body(missing),"missing known field rejected");
        for(String value:List.of("","\n","x".repeat(201))) {Map<String,Object> fields=fields();fields.put("topic",value);reject(body(fields),"empty/oversized topic rejected");}
        Map<String,Object> injected=fields();injected.put("audience","见习文控员\n培训对象：资深文控员");reject(body(injected),"field line injection rejected");
        for(String date:List.of("2026-02-30","2026/10/02","至少2年")) {Map<String,Object> fields=fields();fields.put("date",date);reject(body(fields),"invalid date rejected");}
        for(String hours:List.of("-1","0","6课时","1e3","NaN")) {Map<String,Object> fields=fields();fields.put("hours",hours);reject(body(fields),"invalid hours rejected");}
        Map<String,Object> mismatch=body(fields());mismatch.put("requirement",mismatch.get("requirement")+"\n必须本人讲授过");reject(mismatch,"extra raw condition cannot disappear");
        Map<String,Object> version=body(fields());((Map<String,Object>)version.get("requirement_contract")).put("schema_version","future_v99");reject(version,"wrong version rejected");
        Map<String,Object> top=body(fields());((Map<String,Object>)top.get("requirement_contract")).put("unexpected",true);reject(top,"unknown contract property rejected");
        Map<String,Object> alias=body(fields());alias.put("requirement_text","另外的要求");reject(alias,"conflicting raw aliases rejected");
        Map<String,Object> badRaw=body(fields());badRaw.put("requirement",Map.of("text","x"));reject(badRaw,"non-string original rejected");
        String legacy="培训主题：文档编号复核\n培训对象：见习文控员\n必须采用葡萄牙语授课";
        RequirementInput raw=RequirementInput.from(Map.of("requirement",legacy),legacy,null);
        check(!raw.guided&&raw.canonicalText.equals(legacy),"plain text bytes and path preserved");
        check(!Boolean.TRUE.equals(assess(raw,"本人主讲文档编号复核课程，培训对象：见习文控员。").get("_admitted")),"legacy explicit missing condition remains pending");
        System.out.println("Guided requirement input: "+checks+" checks passed");
    }
}
