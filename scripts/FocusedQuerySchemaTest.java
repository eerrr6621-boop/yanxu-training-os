package com.training;

import java.io.IOException;
import java.util.*;

/** Fixed synthetic projection/field contracts. No model, HTTP, database or CV.
 * --fixtures prints the fixed plan without exercising focus; --observe-baseline
 * preserves old failures without changing expectations or failing the process. */
public final class FocusedQuerySchemaTest {
    private static int checks;
    private static final List<String> failures=new ArrayList<>();
    private static final List<Map<String,Object>> observations=new ArrayList<>();
    private static final List<String> LABELS=List.of("客户单位","培训主题","参训对象","希望解决的问题","期望日期","预计课时","师资要求","补充说明");
    private record Example(int id,String name,String raw,String expected,Map<String,Object> fields,boolean reject) {}
    private static void check(boolean value,String label){checks++;if(!value)failures.add(label);}
    private static Map<String,Object> fields(){
        Map<String,Object> f=new LinkedHashMap<>();for(String key:RequirementInput.FIELDS)f.put(key,"");
        f.put("unit","合成档案中心");f.put("topic","岩芯封装");f.put("audience","现场采样员");
        f.put("goals","掌握标记校验");f.put("date","2026-11-09");f.put("hours","6");
        f.put("preference","本人独立主讲");f.put("extra","不要只有备课经历");return f;
    }
    private static String rendered(Map<String,Object> f){
        List<String> lines=new ArrayList<>();
        for(int i=0;i<LABELS.size();i++){String value=(String)f.get(RequirementInput.FIELDS.get(i));if(!value.strip().isEmpty())lines.add(LABELS.get(i)+"："+value.strip());}
        return String.join("\n",lines);
    }
    private static Map<String,Object> body(String raw,Map<String,Object> f){return new LinkedHashMap<>(Map.of("requirement",raw,
        "requirement_contract",Map.of("schema_version",RequirementInput.VERSION,"fields",f)));}
    private static Example raw(int id,String name,String raw,String expected){return new Example(id,name,raw,expected,null,false);}
    private static List<Example> planned(){
        Map<String,Object> guided=fields(),injected=fields();injected.put("audience","现场采样员\n培训对象：资深巡检员");
        return List.of(
            raw(1,"raw eight fields",rendered(guided),"岩芯封装；参训对象:现场采样员；掌握标记校验；本人独立主讲；不要只有备课经历"),
            new Example(2,"actual guided canonical path",rendered(guided),"岩芯封装；培训对象:现场采样员；掌握标记校验；本人独立主讲；不要只有备课经历",guided,false),
            raw(3,"audience-only fallback","参训对象：现场采样员","参训对象:现场采样员"),
            raw(4,"fullwidth values","　培训主题：ＡＢＣ索扣编号\n　参训对象：第２班采样员　","ABC索扣编号；参训对象:第2班采样员"),
            raw(5,"bounded horizontal label spacing","培训主题 \t: 索扣编号\n参训对象　：　现场采样员","索扣编号；参训对象 : 现场采样员"),
            raw(6,"CRLF fields","培训主题：岩芯封装\r\n参训对象：现场采样员","岩芯封装；参训对象:现场采样员"),
            raw(7,"multiline professional content","培训内容：索扣编号\n扣带归档\n期望日期：2026-11-09","索扣编号；扣带归档"),
            raw(8,"multiline audience then date","培训主题：岩芯封装\n参训对象：现场采样员\n新入职巡检员\n授课日期：2026-11-09","岩芯封装；参训对象:现场采样员；新入职巡检员"),
            new Example(9,"guided field injection rejected",rendered(injected),null,injected,true),
            raw(10,"extra before topic","补充说明：不要只做助教的候选\n培训主题：索扣编号","不要只做助教的候选；索扣编号"),
            raw(11,"extra after operations","培训主题：索扣编号\n授课日期：2026-11-09\n预计课时：6\n补充说明：至少有2场本人实际授课记录","索扣编号；至少有2场本人实际授课记录"),
            raw(12,"negative preference","培训主题：索扣编号\n师资要求：不接受只有备课经历","索扣编号；不接受只有备课经历"),
            raw(13,"unknown exclusion is not promoted","培训主题：索扣编号\n排除主题：装盒包装","索扣编号"),
            raw(14,"original and canonical operational date","培训主题：索扣编号\n期望日期：2026-11-09\n授课日期：2026-11-09","索扣编号"),
            raw(15,"complete operational budget","培训主题：索扣编号\n预算：3000元","索扣编号"),
            raw(16,"literal numeric course name","培训主题：预算3000元核算","预算3000元核算"),
            raw(17,"literal dated content version","培训内容：2026-10-05版规程解读","2026-10-05版规程解读"),
            raw(18,"budget mixed with professional condition","培训主题：索扣编号\n预算：3000元；另需讲师本人实际讲过索扣编号","索扣编号；预算:3000元;另需讲师本人实际讲过索扣编号")
        );
    }
    private static List<Map<String,Object>> fixtureValues(){
        List<Map<String,Object>> rows=new ArrayList<>();
        for(Example e:planned()){Map<String,Object> row=new LinkedHashMap<>();row.put("id",e.id);row.put("name",e.name);row.put("raw",e.raw);row.put("expected_focus",e.expected);row.put("guided_fields",e.fields);row.put("expected_input_rejection",e.reject);rows.add(row);}return rows;
    }
    private static String source(String topic,String audience){return "课程名称："+topic+"\n授课人：本人\n授课角色：独立主讲\n完成状态：已完成\n培训对象："+audience;}
    private static Map<String,Object> assess(String requirement,String biography,RequirementInput guided){
        Map<String,Object> c=new LinkedHashMap<>();c.put("teacher_id",1);c.put("_rule_relevant",true);c.put("professional_score",50.0);c.put("gaps",new ArrayList<String>());
        new RequirementCoverage(requirement).assess(c,biography,false);if(guided!=null)guided.annotate(c);return c;
    }
    private static Map<String,Object> hardSnapshot(String query,RequirementInput input){
        return new LinkedHashMap<>(Map.of("original_hard_query",query,"masked_hard_query",LocalSemantic.requirementInput(query),
            "actual_rule_candidate",assess(query,source("岩芯封装","现场采样员"),input)));
    }
    private static void exercise(Example e){
        RequirementInput input=null;String query=e.raw;Map<String,Object> observation=new LinkedHashMap<>();observation.put("id",e.id);observation.put("name",e.name);observation.put("raw",e.raw);observation.put("expected_focus",e.expected);
        if(e.fields!=null){
            try{input=RequirementInput.from(body(e.raw,e.fields),"旧项目不得混入",83L);query=input.canonicalText;}
            catch(IllegalArgumentException error){check(e.reject,e.id+" unexpected input rejection");observation.put("input_rejection",error.getMessage());observations.add(observation);return;}
        }
        check(!e.reject,e.id+" field injection must reject before focus");
        Map<String,Object> before=hardSnapshot(query,input);
        String actual=LocalSemantic.focusedQuery(query);check(Objects.equals(actual,e.expected),e.id+" "+e.name);
        observation.put("actual_focus",actual);observation.put("expectation_met",Objects.equals(actual,e.expected));observation.put("matching_source",query);
        observation.put("hard_before",before);observation.put("hard_after",hardSnapshot(query,input));
        check(before.equals(observation.get("hard_after")),e.id+" full hard contract unchanged by projection");
        Map<String,Object> candidate=new LinkedHashMap<>(Map.of("teacher_id",1,"score",50.0,"professional_score",50.0));
        List<Map<String,Object>> requests=new ArrayList<>();
        Map<String,Object> state=LocalSemantic.augment(query,List.of(candidate),Map.of(1L,source("岩芯封装","现场采样员")),request->{requests.add(request);throw new IOException("capture-only test transport; no model");});
        check(requests.size()==1&&Objects.equals(requests.get(0).get("query"),actual),e.id+" actual request uses projection");
        check("unavailable".equals(state.get("status"))&&!candidate.containsKey("semantic"),e.id+" capture is not successful inference");
        observation.put("captured_request",requests.isEmpty()?null:requests.get(0));
        if(input!=null){
            observation.put("input_provenance",input.toMap());
            check(input.originalText.equals(e.raw)&&query.contains("培训对象：现场采样员"),"guided current path is already canonical, not previously missing audience");
            check(!query.contains("旧项目"),"no existing demand duplication");
            check(!Boolean.TRUE.equals(((Map<?,?>)before.get("actual_rule_candidate")).get("_admitted")),"guided extras stay pending, never upgraded by focus");
        }
        observations.add(observation);
    }
    private static void additionalBoundaries(){
        for(String query:List.of("培训主题：索扣编号\n预算：3000元\n必须采用未确认的授课条件",
            "期望日期：2026-11-09\n不得把助教经历当作本人主讲\n培训主题：索扣编号",
            "培训主题：索扣编号\n预算：３０００元\u2028必须本人实际主讲过",
            "培训主题：索扣编号\n授课日期：2026-11-09\n\n必须本人实际主讲过")){
            String normalized=LocalSemantic.normalized(query),condition=normalized.contains("未确认")?"必须采用未确认的授课条件":normalized.contains("不得")?"不得把助教经历当作本人主讲":"必须本人实际主讲过";
            check(LocalSemantic.focusedQuery(query).contains(condition),"operational line cannot consume later standalone condition");
        }
        for(String line:List.of("预算：3000元且必须本人主讲过", "期望日期：2026-11-09但只接受本人独立授课", "预计课时：6但至少有2场已完成记录",
                "预算：３０００元\u200b且不得只有备课经历", "授课日期：２０２６-１１-０９📚必须讲过")){
            String query="培训主题：索扣编号\n"+line;
            check(LocalSemantic.focusedQuery(query).contains(LocalSemantic.normalized(line)),"mixed operational value preserved literally");
            check(LocalSemantic.requirementInput(query).contains(line),"mixed hard condition not masked");
        }
        for(String text:List.of("请讲预算3000元核算", "请讲2026-10-05版规程解读", "培训主题：授课日期2026-10-05记录核对", "培训目标：讨论预算3000元的校验步骤")){
            String expected=LocalSemantic.normalized(text).replaceFirst("^(培训主题|培训目标):","");
            check(LocalSemantic.focusedQuery(text).equals(expected),"numeric literal preserved in labelled and raw professional prose");
        }
        for(String aliases:List.of("参训对象","培训对象","授课对象","学员对象","受众"))
            check(LocalSemantic.focusedQuery("培训主题：索扣编号\n"+aliases+"：现场采样员").contains(aliases+":现场采样员"),"all existing and original audience aliases");
        for(String op:List.of("预算：3000元","期望日期：2026-11-09","预计课时：6"))
            check(LocalSemantic.focusedQuery(op).isEmpty(),"only explicit operation does not invent a professional target");
        Map<String,Object> candidate=new LinkedHashMap<>(Map.of("teacher_id",1));
        check("review_required".equals(LocalSemantic.augment("培训主题：索扣编号\n补充说明：仅限男性讲师",List.of(candidate),Map.of(1L,"合成文档"),request->{throw new AssertionError("attribute guard bypassed");}).get("status")),"full personal-attribute guard still precedes projection");
        Map<String,Object> guided=fields();guided.put("preference","");guided.put("extra","必须符合尚未结构化的独立条件📚");
        RequirementInput input=RequirementInput.from(body(rendered(guided),guided),"",null);
        check(LocalSemantic.focusedQuery(input.canonicalText).contains((String)guided.get("extra")),"guided unknown extra retained in retrieval");
        check(!Boolean.TRUE.equals(assess(input.canonicalText,source("岩芯封装","现场采样员"),input).get("_admitted")),"unknown guided extra remains actual pending gate");
        Map<String,Object> standard=fields();standard.put("preference","");standard.put("extra","");standard.put("goals","");
        RequirementInput current=RequirementInput.from(body(rendered(standard),standard),"",null);
        check(LocalSemantic.focusedQuery(current.canonicalText).contains("培训对象:现场采样员"),"standard migrated canonical guided audience reaches request");
        check(Boolean.TRUE.equals(assess(current.canonicalText,source("岩芯封装","资深巡检员"),current).get("_admitted")),"future target does not impose an unstated same-audience history gate");
        Map<String,Object> otherAudience=assess(current.canonicalText,source("岩芯封装","资深巡检员")+"\n\n"+source("另一个独立主题","现场采样员"),current);
        check(Boolean.TRUE.equals(otherAudience.get("_admitted")),"supported topic remains eligible with adaptation pending");
        check(((List<?>)otherAudience.get("audience_context")).stream().map(v->(Map<?,?>)v).noneMatch(v->Boolean.TRUE.equals(v.get("source_audience_type_supported"))),"another course cannot lend audience evidence to context audit");
        Map<String,Object> unicode=fields();unicode.put("unit","📚");unicode.put("topic","ＡＢＣ索扣编号");RequirementInput provenance=RequirementInput.from(body(rendered(unicode),unicode),"",null);
        String before=Json.write(provenance.toMap());LocalSemantic.focusedQuery(provenance.canonicalText);check(Json.write(provenance.toMap()).equals(before),"original/canonical Unicode ledger unchanged");
        for(Object raw:(List<?>)provenance.toMap().get("field_ledger")){
            Map<?,?> field=(Map<?,?>)raw;
            for(String key:List.of("original_span","canonical_span"))if(field.get(key) instanceof Map<?,?> span){
                String original=key.equals("original_span")?provenance.originalText:provenance.canonicalText;
                int start=original.offsetByCodePoints(0,((Number)span.get("source_start")).intValue()),end=original.offsetByCodePoints(0,((Number)span.get("source_end")).intValue());
                check(original.substring(start,end).equals(span.get("text")),"Unicode source coordinate stays attached to its actual source");
            }
        }
    }
    public static void main(String[] args){
        if(args.length==1&&args[0].equals("--fixtures")){System.out.println(Json.write(Map.of("planned_cases",fixtureValues(),"executed",false)));return;}
        boolean baseline=args.length==1&&args[0].equals("--observe-baseline");if(args.length>0&&!baseline)throw new IllegalArgumentException("unknown argument");
        for(Example e:planned())exercise(e);additionalBoundaries();
        System.out.println(Json.write(Map.of("checks",checks,"failures",failures,"planned_cases",18,"observations",observations,
            "mode",baseline?"unchanged_expectations_baseline_observation":"current_contract","model_runs",0,"production_touched",false)));
        if(!baseline&&!failures.isEmpty())throw new AssertionError(failures.size()+" focused schema failures");
    }
}
