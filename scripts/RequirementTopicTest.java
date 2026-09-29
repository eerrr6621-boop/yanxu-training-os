package com.training;

import java.util.*;

/** Fixed development pairs for a bounded requirement AST; not blind/model evaluation. */
public final class RequirementTopicTest {
    private static int passed;
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);passed++;}
    private static RequirementTopic.Course sole(RequirementTopic.Result result,String title) {
        check(result.courses().size()==1 && result.courses().get(0).canonicalAnchor().equals(title),"one exact course: "+result.toMap());
        return result.courses().get(0);
    }
    private static void lossless(RequirementTopic.Result result) {
        String source=result.source();boolean[] covered=new boolean[source.length()];
        for(RequirementTopic.Consumption c:result.consumedSpans()) {
            checkSpan(source,c.span());for(int i=c.span().start();i<c.span().end();i++)covered[i]=true;
        }
        for(RequirementTopic.Unparsed c:result.unparsedClauses()) {
            checkSpan(source,c.span());for(int i=c.span().start();i<c.span().end();i++)covered[i]=true;
        }
        for(int i=0;i<source.length();i++) if(!Character.isWhitespace(source.charAt(i)) && !"，,。；;：:《》！？!?、（）()".contains(source.substring(i,i+1)))
            check(covered[i],"No semantic character silently discarded at "+i+": "+source);
        for(RequirementTopic.Course course:result.courses()) {
            for(RequirementTopic.Span span:course.anchorSpans()) checkSpan(source,span);
            for(RequirementTopic.Span span:course.referenceSpans()) checkSpan(source,span);
            for(RequirementTopic.HistoryCondition h:course.historyConditions()) checkSpan(source,h.span());
        }
    }
    private static void checkSpan(String source,RequirementTopic.Span span) {
        check(span.start()>=0 && span.end()<=source.length() && source.substring(span.start(),span.end()).equals(span.text()),"exact contiguous source span");
        Map<String,Object> map=span.toMap(source,"requirement_original");
        int a=((Number)map.get("source_start")).intValue(),b=((Number)map.get("source_end")).intValue();
        check(source.substring(source.offsetByCodePoints(0,a),source.offsetByCodePoints(0,b)).equals(map.get("text")),"code point offsets reconstruct original");
    }
    public static void main(String[] args) {
        for(String title:List.of("陶瓷釉料调配","无人机螺旋桨养护","冷藏药品入库复核")) {
            RequirementTopic.Result basic=RequirementTopic.parse("培训主题："+title);
            check(!sole(basic,title).requiresHistory(),"topic alone not past experience");
            check(basic.unparsedClauses().isEmpty(),"complete bare field represented");
            RequirementTopic.Result reference=RequirementTopic.parse("培训主题："+title+"；必须有这门课的经历");
            RequirementTopic.Course c=sole(reference,title);
            check(c.requiresHistory() && c.referenceSpans().size()==1,"single course history reference attached");
            check(reference.unparsedClauses().isEmpty(),"supported history clause fully represented");
            check(sole(RequirementTopic.parse("需要本人已教过《"+title+"》"),title).requiresHistory(),"active completed aspect linked to named course");
            check(sole(RequirementTopic.parse("需要本人已教过"+title+"课程"),title).requiresHistory(),"unquoted teaching object shares named node grammar");
            check(sole(RequirementTopic.parse("必须本人已完成《"+title+"》的教学"),title).requiresHistory(),"past completed course instruction");
            RequirementTopic.Course completed=sole(RequirementTopic.parse("必须本人独立讲完《"+title+"》并有课堂记录"),title);
            check(completed.requiresHistory() && completed.historyConditions().stream().anyMatch(h->h.personal()&&h.completed()&&h.independent()),"completed personal independent condition carried");
            check(sole(RequirementTopic.parse("课程：《"+title+"》；要求该课程有实际授课记录"),title).requiresHistory(),"history from reference before predicate");
            check(!sole(RequirementTopic.parse("培训主题："+title+"\n培训目标：学员在培训后能够独立完成操作"),title).requiresHistory(),"future learner goal not instructor history");
            RequirementTopic.Result planned=RequirementTopic.parse("计划下月讲授《"+title+"》");
            check(planned.courses().isEmpty() && !planned.unparsedClauses().isEmpty(),"planned ambiguous prose stays pending not completed course");
            RequirementTopic.Result negative=RequirementTopic.parse("不需要讲过《"+title+"》");
            check(negative.courses().isEmpty() && !negative.unparsedClauses().isEmpty(),"negated experience not positive obligation");
            RequirementTopic.Result wrongRole=RequirementTopic.parse("培训主题："+title+"；学员必须已学过这门课");
            check(!sole(wrongRole,title).requiresHistory() && !wrongRole.unparsedClauses().isEmpty(),"learner prerequisite not teacher history");
            RequirementTopic.Result unknown=RequirementTopic.parse("培训主题："+title+"；还必须满足特殊资质与课堂活动要求");
            check(sole(unknown,title).historyConditions().isEmpty() && !unknown.unparsedClauses().isEmpty(),"unknown condition not erased or fake topic");
            RequirementTopic.Result targets=RequirementTopic.parse("培训主题："+title+"\n培训对象：设备管理员\n师资要求：必须有这门课的授课经历");
            check(sole(targets,title).requiresHistory() && targets.unparsedClauses().stream().anyMatch(u->u.span().text().contains("设备管理员")),"audience preserved for downstream condition parser");
            lossless(reference);lossless(unknown);lossless(targets);
        }
        String a="陶瓷釉料调配",b="冷藏药品入库复核";
        RequirementTopic.Result two=RequirementTopic.parse("培训主题：《"+a+"》和《"+b+"》");
        check(two.courses().stream().map(RequirementTopic.Course::canonicalAnchor).toList().equals(List.of(a,b)),"explicit conjunction retains two courses");
        RequirementTopic.Result ambiguous=RequirementTopic.parse("培训主题：《"+a+"》和《"+b+"》；必须有这门课的授课经历");
        check(ambiguous.courses().stream().noneMatch(RequirementTopic.Course::requiresHistory) && !ambiguous.unparsedClauses().isEmpty(),"singular reference never chooses from two antecedents");
        RequirementTopic.Result each=RequirementTopic.parse("培训主题：《"+a+"》和《"+b+"》；上述两门课程均须有实际授课记录");
        check(each.courses().size()==2 && each.courses().stream().allMatch(RequirementTopic.Course::requiresHistory),"explicit distributive plural binds both");
        RequirementTopic.Result named=RequirementTopic.parse("培训主题：《"+a+"》和《"+b+"》；必须已教过《"+a+"》");
        check(named.courses().size()==2 && named.courses().get(0).requiresHistory() && !named.courses().get(1).requiresHistory(),"named condition does not become global history");
        RequirementTopic.Result implicit=RequirementTopic.parse("培训主题："+a+"；必须有实际授课记录");
        check(sole(implicit,a).requiresHistory(),"sole anchor supports implicit history target");
        RequirementTopic.Result orphan=RequirementTopic.parse("必须有这门课的经历");
        check(orphan.courses().isEmpty() && !orphan.unparsedClauses().isEmpty(),"orphan pronoun stays pending");
        RequirementTopic.Result forward=RequirementTopic.parse("必须有这门课的经历；培训主题："+a);
        check(!sole(forward,a).requiresHistory() && !forward.unparsedClauses().isEmpty(),"no speculative forward reference");
        RequirementTopic.Result title=RequirementTopic.parse("培训主题：《市场与价格》");
        sole(title,"市场与价格");check(title.unparsedClauses().isEmpty(),"quoted conjunction inside title not two courses");
        RequirementTopic.Result alternative=RequirementTopic.parse("培训主题：《"+a+"》或《"+b+"》");
        check(alternative.courses().isEmpty() && !alternative.unparsedClauses().isEmpty(),"OR is not silently turned into AND");
        RequirementTopic.Result distribution=RequirementTopic.parse("培训主题："+a+"；必须有这门课至少三次授课经历");
        check(!distribution.unparsedClauses().isEmpty(),"numeric condition not consumed merely as history");
        RequirementTopic.Result quotedUnknown=RequirementTopic.parse("补充说明：请遵守《甲方课堂守则》");
        check(quotedUnknown.courses().isEmpty(),"arbitrary quoted document not automatically a course");
        RequirementTopic.Result future=RequirementTopic.parse("培训主题："+a+"\n培训目标：要求老师在本次课程结束时完成讲授");
        check(!sole(future,a).requiresHistory() && !future.unparsedClauses().isEmpty(),"future goal teacher task not previous completion");
        RequirementTopic.Result mixed=RequirementTopic.parse("培训主题："+a+"；必须本人已教过这门课并让学员现场完成练习");
        check(!mixed.unparsedClauses().isEmpty(),"extra classroom activity cannot disappear in history suffix");
        RequirementTopic.Result other=RequirementTopic.parse("培训主题："+a+"；同事必须已教过这门课");
        check(!sole(other,a).requiresHistory() && !other.unparsedClauses().isEmpty(),"other actor not personal history");
        RequirementTopic.Result exactTitle=RequirementTopic.parse("必须教过《本人独立操作记录》");
        RequirementTopic.Course namedFlags=sole(exactTitle,"本人独立操作记录");
        check(namedFlags.historyConditions().stream().noneMatch(h->h.personal()||h.independent()),"actor and independence words inside title cannot become conditions");
        RequirementTopic.Result plainDocument=RequirementTopic.parse("补充说明：《甲方课堂守则》");
        check(plainDocument.courses().isEmpty()&&!plainDocument.unparsedClauses().isEmpty(),"bare quoted supplemental document not assumed to be a course");
        RequirementTopic.Result futureCompletion=RequirementTopic.parse("培训主题："+a+"；必须本人完成这门课的教学");
        check(!sole(futureCompletion,a).requiresHistory() && !futureCompletion.unparsedClauses().isEmpty(),"completion without past or history marker remains temporally ambiguous");
        RequirementTopic.Result mismatchPlural=RequirementTopic.parse("培训主题：《"+a+"》和《"+b+"》；上述三门课程均须有实际授课记录");
        check(mismatchPlural.courses().stream().noneMatch(RequirementTopic.Course::requiresHistory)&&!mismatchPlural.unparsedClauses().isEmpty(),"plural cardinality cannot exceed actual antecedents");
        RequirementTopic.Result separated=RequirementTopic.parse("培训主题："+a+"\n\n必须有这门课的经历");
        check(!sole(separated,a).requiresHistory()&&!separated.unparsedClauses().isEmpty(),"blank paragraph does not license implicit cross-section reference");
        RequirementTopic.Result wrongAction=RequirementTopic.parse("培训主题："+a+"；必须已完成这门课的讲义");
        check(!sole(wrongAction,a).requiresHistory()&&!wrongAction.unparsedClauses().isEmpty(),"completed materials are not completed teaching condition");
        RequirementTopic.Result unicode=RequirementTopic.parse("补充说明：🧪边界\n培训主题：《３Ｄ模型修补》；必须有这门课的经历");
        check(sole(unicode,"3D模型修补").requiresHistory(),"canonical NFKC anchor keeps original spans");
        lossless(two);lossless(ambiguous);lossless(each);lossless(named);lossless(alternative);lossless(mixed);lossless(unicode);
        Map<String,Object> fields=new LinkedHashMap<>();for(String key:RequirementInput.FIELDS)fields.put(key,"");
        fields.put("topic",a);fields.put("audience","设备管理员");fields.put("goals","掌握现场复核步骤");fields.put("preference","必须有这门课的经历");
        String original="培训主题："+a+"\n参训对象：设备管理员\n希望解决的问题：掌握现场复核步骤\n师资要求：必须有这门课的经历";
        RequirementInput input=RequirementInput.from(Map.of("requirement",original,"requirement_contract",Map.of("schema_version",RequirementInput.VERSION,"fields",fields)),"",null);
        RequirementTopic.Result guided=RequirementTopic.parse(input);
        check(guided.sourceKind().equals("requirement_canonical") && guided.source().equals(input.canonicalText),"guided canonical not falsely labelled raw original");
        check(sole(guided,a).requiresHistory(),"guided topic and preference bind same course");
        check(guided.unparsedClauses().stream().anyMatch(u->u.span().text().contains("设备管理员")),"guided audience remains explicitly carried onward");
        lossless(guided);
        System.out.println("Requirement topic: "+passed+" fixed assertions passed");
    }
}
