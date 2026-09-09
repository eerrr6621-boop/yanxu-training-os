package com.training;

import java.util.*;

/** New synthetic contract pairs, not a model benchmark or verified biography. */
public final class RequirementContractTest {
    static int checks;
    static void check(boolean value,String label) { if(!value)throw new AssertionError(label);checks++; }
    static Map<String,Object> assess(String q,String source) {
        Map<String,Object> c=new LinkedHashMap<>();c.put("_rule_relevant",true);c.put("professional_score",50.0);c.put("gaps",new ArrayList<String>());
        c.put("semantic",Map.of("similarity",.999,"evidence_complete",true));new RequirementCoverage(q).assess(c,source,true);return c;
    }
    static void expect(boolean value,String q,String source,String label) {
        Map<String,Object> c=assess(q,source);check(Boolean.TRUE.equals(c.get("_admitted"))==value,label+" "+Json.write(c));
    }
    public static void main(String[] args) {
        for(String field:List.of("授课对象","培训对象","学员对象","受众")) {
            String q="培训主题：古籍页码核对\n"+field+"：新任管理员";
            check(LocalSemantic.focusedQuery(q).contains(field+":新任管理员"),"retrieval retains labelled audience "+field);
            check(new RequirementConstraints(q).audienceIntent.contexts.stream().anyMatch(c->c.audience().equals("新任管理员")),"full query retains non-hard future audience "+field);
            expect(true,q,"本人主讲古籍页码核对课程，"+field+"：新任管理员。","matching audience "+field);
            expect(true,q,"本人主讲古籍页码核对课程。","future audience missing from resume is not a history requirement "+field);
            expect(true,q,"本人主讲古籍页码核对课程，受众：资深管理员。","different past audience requires adaptation confirmation, not automatic rejection "+field);
            expect(true,q,"本人主讲古籍页码核对课程。\n本人主讲餐具消毒记录课程，受众：新任管理员。","supported topic remains eligible without borrowing other-course audience "+field);
            String qualified=q+"（无编程经验）";
            check(new RequirementConstraints(qualified).audienceIntent.contexts.stream().anyMatch(c->c.audience().equals("新任管理员（无编程经验）")),"descriptive audience qualifier retained as context "+field);
            expect(true,qualified,"本人主讲古籍页码核对课程，受众：新任管理员。","descriptive future qualifier does not create historical qualification "+field);
            expect(true,qualified,"本人主讲古籍页码核对课程，受众：新任管理员（无编程经验）。","explicit full qualifier supported "+field);
            RequirementConstraints learnerYears=new RequirementConstraints("培训主题：古籍页码核对\n"+field+"：至少3年工作经验的管理员");
            check(learnerYears.minimums.isEmpty()&&!learnerYears.teachingHistory,"learner experience is not instructor teaching years "+field);
            check(learnerYears.features.containsKey("授课对象：至少3年工作经验的管理员"),"learner number remains an audience condition "+field);
        }
        String operational="培训主题：冷链温度记录\n计划日期：2026-09-20\n预算：不超过2000元\n受众：新员工（1年内入职）\n师资要求：每场不少于120分钟";
        String masked=LocalSemantic.requirementInput(operational);
        check(masked.length()==operational.length(),"operational masking preserves UTF16 offsets");
        check(masked.indexOf("受众：")==operational.indexOf("受众："),"audience offset unchanged");
        check(!masked.contains("2026-09-20")&&!masked.contains("2000")&&masked.contains("1年内入职")&&masked.contains("120分钟"),"only exact date and money metadata masked");
        RequirementConstraints c=new RequirementConstraints(operational);
        check(c.minimums.size()==1&&Objects.equals(c.minimums.get(0).get("minimum"),120),"money and plan date never teaching durations");
        check(LocalSemantic.requirementInput("历史授课时间：至少2年").equals("历史授课时间：至少2年"),"historical teaching duration not stripped");
        check(LocalSemantic.requirementInput("授课时间：每场至少120分钟").equals("授课时间：每场至少120分钟"),"non-date teaching time not stripped");
        check(LocalSemantic.requirementInput("预算：必须包含课堂练习").equals("预算：必须包含课堂练习"),"non-monetary field not silently dropped");
        for(String[] bracket:List.of(new String[]{"（","）"},new String[]{"(",")"})) for(String separator:List.of("；",";")) {
            String audience="新任管理员"+bracket[0]+"零基础"+separator+"必须持证"+bracket[1];
            String query="培训主题：古籍页码核对\n受众："+audience;
            expect(false,query,"本人主讲古籍页码核对课程，受众：新任管理员（零基础）。","internal semicolon must not drop remaining qualifier");
            RequirementConstraints bounded=new RequirementConstraints(query);
            check(bounded.unknownExclusion&&bounded.audienceReview.stream().anyMatch(v->v.contains(audience)),"full semicolon-qualified audience retained for review");
            String unbalanced="培训主题：古籍页码核对\n受众：新任管理员"+bracket[0]+"零基础";
            expect(false,unbalanced,"本人主讲古籍页码核对课程，受众：新任管理员（零基础）。","unclosed qualifier pending");
            expect(false,"培训主题：古籍页码核对\n受众：新任管理员"+bracket[1],"本人主讲古籍页码核对课程，受众：新任管理员。","unmatched closing qualifier pending");
            expect(true,"培训主题：古籍页码核对\n受众：新任管理员"+bracket[0]+"零基础"+bracket[1],"本人主讲古籍页码核对课程，受众：新任管理员（零基础）。","simple balanced qualifier remains supported");
        }
        String timed="培训主题：冷链温度记录\n预算：2000元\n授课日期：2026-09-20\n师资要求：至少120分钟";
        expect(true,timed,"本人主讲冷链温度记录课程，实际授课120分钟。","net instructional duration matches");
        expect(false,timed,"本人主讲冷链温度记录课程，实际授课30分钟，课程费用120元。","money cannot fill instructional minutes");
        for(String topic:List.of("古籍页码核对","冷链温度记录","园艺工具清洁")) {
            for(String verb:List.of("主讲过","讲授过","主讲了","讲授了")) {
                String q="需要本人"+verb+topic+"课程的课堂记录";
                RequirementCoverage coverage=new RequirementCoverage(q);
                check(coverage.required.keySet().stream().noneMatch(k->k.contains("主题：过")||k.contains("主题：了")),"aspect consumed "+verb+topic);
                expect(true,q,"2025年本人主讲过"+topic+"课程。","explicit same subject historical event "+verb);
                expect(false,q,"主讲课程："+topic,"title alone not historical event "+verb);
                expect(false,q,"本人仅担任"+topic+"课程助教。","same subject assistant not history "+verb);
            }
            for(String title:List.of("培训主题：《"+topic+"》","《"+topic+"》")) {
                String q=title+"；需要本人讲授过这一主题的课堂记录";
                check(new RequirementCoverage(q).required.keySet().stream().noneMatch(k->k.contains("这一主题")),"unambiguous reference does not become new course");
                expect(true,q,"2025年本人主讲过"+topic+"课程。","single title reference binds historical subject");
                expect(false,q,"主讲课程："+topic,"reference cannot turn title into history");
            }
            String completed="需要本人独立讲完"+topic+"课程的课堂记录";
            check(new RequirementConstraints(completed).teachingHistory,"finished classroom record requires history");
            expect(false,completed,"主讲课程："+topic,"finished classroom record cannot use catalog");
            expect(false,completed,"本人担任"+topic+"课程助教。","finished classroom record cannot use assistant");
            expect(true,completed,"2025年本人主讲过"+topic+"课程。","completed teaching requirement accepts genuine history");
        }
        expect(false,"需要本人讲授过这一主题的课堂记录","2025年本人主讲过冷链温度记录课程。","reference without antecedent pending");
        expect(false,"培训主题：冷链温度记录和古籍页码核对；需要本人讲授过这一主题的课堂记录","2025年本人主讲过冷链温度记录和古籍页码核对课程。","singular reference to two subjects ambiguous");
        for(String object:List.of("报修","退换货","涉税")) {
            String q="培训主题："+object+"处理",ability="本人精通"+object+"业务办理。";
            expect(true,q,ability,"one-way exact object business handling "+object);
            expect(false,"培训主题："+object+"业务办理","本人精通"+object+"处理。","reverse entailment not assumed "+object);
            expect(false,q,"本人精通"+object+"登记。","registration is not handling "+object);
            expect(false,q,"本人精通"+object+"审核。","audit is not handling "+object);
            expect(false,q,"本人精通业务"+object+"办理。","reordered business noun not assumed "+object);
            expect(false,q,"同事精通"+object+"业务办理。","colleague capability not own "+object);
            expect(false,q,"精通"+object+"业务办理的是同事。","reverse colleague capability not own "+object);
            expect(false,q,"本团队擅长"+object+"业务办理。","team capability not own "+object);
            expect(false,q,"本人精通"+object+"知识；另精通无关业务办理。","object and action cannot cross claims "+object);
            expect(false,q+"；必须有实际授课记录",ability,"content capability never invents teaching history "+object);
            Map<String,Object> outcome=assess(q,ability);
            Map<?,?> item=(Map<?,?>)((List<?>)outcome.get("requirement_coverage")).get(((List<?>)outcome.get("requirement_coverage")).size()-1);
            check(((List<?>)outcome.get("requirement_coverage")).stream().anyMatch(raw->raw instanceof Map<?,?> row&&row.get("requirement_source_span") instanceof Map<?,?> span&&q.contains(Objects.toString(span.get("text")))&&Objects.toString(row.get("evidence")).contains(object+"业务办理")),"aligned content retains exact original evidence/query span "+object);
            check(Boolean.FALSE.equals(item.get("verified")),"alignment is not credential verification "+object);
        }
        System.out.println("Requirement contracts: "+checks+" checks passed");
    }
}
