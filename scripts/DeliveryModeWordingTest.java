package com.training;
import java.util.*;

/** Authored delivery label grammar/scope checks; not independent model accuracy. */
public final class DeliveryModeWordingTest {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static final List<String> LABELS=List.of("采用","使用","授课方式为","授课方式是","教学方式为","教学方式是","授课形式为","授课形式是","教学形式为","教学形式是","方式为","方式是","形式为","形式是");
    static final List<String> MODES=List.of("到场面授","现场面授","线上录播","在线录屏","远程直播","远程实时","线上实时","线上直播","远程");
    static boolean onsite(String mode){return mode.endsWith("面授");}
    static boolean live(String mode){return mode.contains("直播")||mode.contains("实时");}
    static void classification(String value,String mode){
        check(InstructorMode.field(value,"面对面授课形式")==onsite(mode),"onsite "+value);
        check(InstructorMode.field(value,"远程授课形式")!=onsite(mode),"remote "+value);
        check(InstructorMode.field(value,"线上实时教学")==live(mode),"live "+value);
    }
    public static void main(String[] args){
        for(String label:LABELS)for(String mode:MODES){
            String clause=label+mode;InstructorMode.Continuation parsed=InstructorMode.continuation(clause);
            check(parsed!=null,"bounded delivery label "+clause);
            check(parsed.mode().equals(mode),"literal not substituted");
            check(clause.substring(parsed.start(),parsed.end()).equals(mode),"literal offsets");
            classification(mode,mode);classification(clause,mode);
            for(String topic:List.of("陶器编号复核","苗圃记录整理","样张🧩复核")){
                String lead="我曾给带教师傅完整讲授《"+topic+"》。",text=lead+clause+"。";
                TeachingEvents.Event event=ActiveTeachingFieldsTest.event(text);
                check(event.value("mode").equals(mode),"bound to established event");
                check(event.completedPersonalTeaching(),"still completed own course");
                check(ActiveTeachingFieldsTest.admitted(topic,"在现场",text)==onsite(mode),"business onsite condition");
                check(ActiveTeachingFieldsTest.admitted(topic,"远程",text)!=onsite(mode),"business remote condition");
                ActiveTeachingFieldsTest.spans(text);
            }
        }
        String topic="展柜物品登记",lead="我曾给带教师傅完整讲授《"+topic+"》。";
        for(String mode:MODES){
            String explicit="授课方式为"+mode+"。";
            String learner=lead+"学员提交作业，"+explicit;
            check(ActiveTeachingFieldsTest.event(learner).value("mode").equals(mode),"explicit teaching label after learner");
            String implicit=lead+"学员提交作业，采用"+mode+"。";
            check(ActiveTeachingFieldsTest.event(implicit).value("mode").isBlank(),"bare learner antecedent not teacher");
            for(String bad:List.of("计划授课方式为","学员授课方式为","同事授课方式为","可能授课方式为","并非授课方式为")){
                String text=lead+bad+mode+"。";
                check(!ActiveTeachingFieldsTest.admitted(topic,"在现场",text),"qualified/other form not accepted");
                check(!ActiveTeachingFieldsTest.admitted(topic,"远程",text),"qualified/other form not accepted remote");
                ActiveTeachingFieldsTest.spans(text);
            }
            String noPast="我给带教师傅完整讲授《"+topic+"》，"+explicit;
            check(!ActiveTeachingFieldsTest.event(noPast).completedPersonalTeaching(),"mode creates no history");
            String other="同事曾给带教师傅完整讲授《"+topic+"》，"+explicit;
            check(!ActiveTeachingFieldsTest.admitted(topic,"在现场",other),"not other teacher onsite");
            check(!ActiveTeachingFieldsTest.admitted(topic,"远程",other),"not other teacher remote");
            String direct=onsite(mode)?"我"+mode:"我"+mode+"授课";
            InstructorMode.SourceDeclaration personal=InstructorMode.sourceDeclaration(direct);
            check(personal!=null&&personal.mode().equals(mode),"personal literal form "+direct);
            check(direct.substring(personal.modeStart(),personal.modeEnd()).equals(mode),"personal mode offsets");
            classification(direct,mode);
        }
        for(String tail:List.of("授课方式为到场面授或线上录播","授课方式为到场面授和远程直播","授课方式为到场面授视频",
                "授课方式为未知","“授课方式为到场面授”","授课方式为到场面授。授课方式为线上录播",
                "\n\n授课方式为到场面授","我曾主讲《另一门课》。授课方式为到场面授")){
            String text=lead+tail;
            check(!ActiveTeachingFieldsTest.admitted(topic,"在现场",text),"conflict/cross course "+tail);
            ActiveTeachingFieldsTest.spans(text);
        }
        check(InstructorMode.continuation("授课方式为到场面授或线上录播")==null,"ambiguous mixed modes");
        check(InstructorMode.continuation("授课方式为到场线上")==null,"arrival is not remote delivery");
        System.out.println("Delivery mode wording checks: "+checks+"; shared span assertions: "+ActiveTeachingFieldsTest.checks);
    }
}
