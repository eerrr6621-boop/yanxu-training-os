package com.training;
import java.util.*;

/** Relative request syntax and source binding, not fresh model acceptance. */
public final class RelativeHistoricalRequestTest {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static void spans(RequirementTopic.Result result){
        for(var course:result.courses()){
            List<RequirementTopic.Span> values=new ArrayList<>(course.anchorSpans());
            for(var h:course.historyConditions()){values.add(h.span());values.addAll(h.audienceBindings());values.addAll(h.modeBindings());}
            for(var s:values){
                check(result.source().substring(s.start(),s.end()).equals(s.text()),"literal source");
                var m=s.toMap(result.source(),"requirement_original");
                int a=result.source().offsetByCodePoints(0,((Number)m.get("source_start")).intValue()),b=result.source().offsetByCodePoints(0,((Number)m.get("source_end")).intValue());
                check(result.source().substring(a,b).equals(s.text()),"unicode wire source");
            }
        }
    }
    public static void main(String[] args){
        for(String prefix:List.of("需要","请找","请推荐","希望找到"))for(String topic:List.of("陶器编号复核","种苗挂牌核对","学习方法","样张🧩复核"))
        for(String learner:List.of("带教师傅","未成年学员","回访坐席"))for(String quote:List.of("","《》","“”")){
            String object=quote.isEmpty()?topic:quote.charAt(0)+topic+quote.charAt(1);
            String query=prefix+"过去亲自给"+learner+"完整教过"+object+"的老师";
            var fact=HistoricalTeachingRequest.parse(query);check(fact!=null,"relative head "+query);
            check(fact.topic().text().equals(topic),"topic not teacher suffix");
            check(fact.audience().text().equals(learner),"audience not action modifiers");
            check(fact.completed()&&!fact.independent(),"complete/personal not independent");
            var result=RequirementTopic.parse(query);check(result.courses().size()==1,"one course");
            var h=result.courses().get(0).historyConditions().get(0);
            check(h.personal()&&h.pastTeaching()&&h.completed(),"relative teacher owns past action");
            check(h.audienceBindings().get(0).text().equals(learner),"audience same course");
            check(result.unparsedClauses().isEmpty(),"bounded clause consumed");spans(result);
            String source="《"+topic+"》已由我给"+learner+"完整讲完。";
            check(HistoricalRequestTest.admitted(query,source),"same course and audience");
            check(!HistoricalRequestTest.admitted(query,source.replace(learner,"库房管理员")),"wrong recipient");
            check(!HistoricalRequestTest.admitted(query,source.replace("我","同事")),"wrong instructor");
            check(!HistoricalRequestTest.admitted(query,source.replace("完整讲完","主讲")),"not complete whole course");
        }
        for(String mode:List.of("在现场","远程"))for(String topic:List.of("展柜物品登记","标本编号核对")){
            String query="需要过去"+mode+"给带教师傅完整教过"+topic+"的讲师";
            var c=RequirementTopic.parse(query);check(c.courses().size()==1,"mode request course");
            check(c.courses().get(0).historyConditions().get(0).modeBindings().get(0).text().equals(mode),"mode same course");spans(c);
            String lead="《"+topic+"》已由我给带教师傅完整讲完。";
            check(HistoricalRequestTest.admitted(query,lead+"授课方式为"+(mode.equals("在现场")?"到场面授":"远程直播")+"。"),"same mode");
            check(!HistoricalRequestTest.admitted(query,lead+"授课方式为"+(mode.equals("在现场")?"远程直播":"到场面授")+"。"),"wrong mode");
        }
        check(!HistoricalTeachingRequest.parse("需要过去亲自给带教师傅教过陶器编号复核的老师").completed(),"past not whole course");
        check(HistoricalTeachingRequest.parse("需要给带教师傅讲完过陶器编号复核的老师").completed(),"completed verb provides past");
        for(String bad:List.of("需要计划给带教师傅完整教过陶器编号复核的老师","需要给带教师傅完成过项目复核的老师",
            "需要过去给经理安排同事完整教过陶器编号复核的老师","需要过去给学员不完整教过陶器编号复核的老师",
            "需要过去给学员基本完整教过陶器编号复核的老师","需要过去给学员必须英语授课完整教过陶器编号复核的老师",
            "需要过去在现场给学员完整远程教过陶器编号复核的老师","需要过去给学员完整教过雕刻或者绘图的老师",
            "需要过去给学员完整教过陶器编号复核且每次不少于三小时的老师","需要过去给学员完整教过“陶器编号复核》的老师",
            "需要过去给学员完整教过《甲课程》与《乙课程》的老师","需要过去给学员完整教过陶器编号复核",
            "需要过去给学员完整教过陶器编号复核的老师但实际是同事授课","需要过去给学员完整教过《陶器编号复核》的老师并且每次三小时"))
            check(HistoricalTeachingRequest.parse(bad)==null,"unknown qualification not consumed "+bad);
        var quoted=HistoricalTeachingRequest.parse("需要曾给带教师傅教过《完整独立现场授课记录》的老师");
        check(quoted!=null&&!quoted.completed()&&!quoted.independent()&&quoted.mode()==null,"quoted words inert");
        String query="需要过去亲自给带教师傅完整教过标本编号核对的老师；必须每次现场完成三小时";
        String source="《标本编号核对》已由我给带教师傅完整讲完。我在现场面授。";
        check(!HistoricalRequestTest.admitted(query,source),"unknown followup not erased");
        String original="需要过去亲自给回访坐席完整教过通话纪要整理的老师，课堂要教如何分清已核实内容和待确认说法，并记录下一步联系安排。";
        var result=RequirementTopic.parse(original);check(result.courses().get(0).canonicalAnchor().equals("通话纪要整理"),"original exposed PD30 topic source");
        check(result.courses().get(0).historyConditions().get(0).audienceBindings().get(0).text().equals("回访坐席"),"original request audience bound");
        check(!result.unparsedClauses().isEmpty(),"remaining content not claimed consumed");spans(result);
        System.out.println("Relative historical request checks: "+checks);
    }
}
