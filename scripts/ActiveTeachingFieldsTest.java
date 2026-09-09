package com.training;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Authored syntax/scope regressions, not model accuracy or blind acceptance. */
public final class ActiveTeachingFieldsTest {
    static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static TeachingEvents.Event event(String raw){
        List<TeachingEvents.Event> e=TeachingEvents.extract(raw);check(e.size()==1,"one event: "+raw);return e.get(0);
    }
    static boolean admitted(String topic,String mode,String raw){
        Map<String,Object> c=new LinkedHashMap<>();c.put("_rule_relevant",true);c.put("professional_score",80.0);c.put("gaps",new ArrayList<>());
        new RequirementCoverage("要找本人以前"+mode+"给带教师傅完整教过《"+topic+"》课程的讲师").assess(c,raw,false);
        return Boolean.TRUE.equals(c.get("_admitted"));
    }
    static void spans(String raw){
        String source=ProfessionalEvidence.normalized(raw);
        for(TeachingEvents.Event e:TeachingEvents.extract(raw)){
            check(e.map().get("text").equals(source.substring(e.start,e.end)),"event source");
            for(TeachingEvents.Field f:e.fields.values()){
                check(f.value().equals(source.substring(f.start(),f.end())),"field source: "+f.label());
                check(e.start<=f.start()&&f.end()<=e.end,"field inside own event: "+f.label());
            }
            for(Map<String,Object> r:e.relations){
                int a=source.offsetByCodePoints(0,((Number)r.get("source_start")).intValue()),b=source.offsetByCodePoints(0,((Number)r.get("source_end")).intValue());
                check(r.get("text").equals(source.substring(a,b)),"relation source");check(e.start<=a&&b<=e.end,"relation inside own event");
            }
        }
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception{
        if(args.length>0&&args[0].equals("--inspect")){
            List<Object> rows=(List<Object>)Json.parse(new String(System.in.readAllBytes(),StandardCharsets.UTF_8));
            System.out.println(Json.write(rows.stream().map(r->TeachingEvents.extract((String)r).stream().map(TeachingEvents.Event::map).toList()).toList()));return;
        }
        for(String topic:List.of("离岗权限回收","古籍页码核对","园艺工具清洁","样张🧩复核")){
            for(String modifier:List.of("完整","完整独立","独立完整","亲自完整")){
                String lead="我已给带教师傅"+modifier+"讲授《"+topic+"》";
                for(String mode:List.of("现场面授","面对面","线下面授","在线录屏","录播","远程","线上实时")){
                    String s=lead+"，采用"+mode+"。";TeachingEvents.Event e=event(s);
                    check(e.issues.isEmpty(),"bounded event "+s);check(e.value("audience").equals("带教师傅"),"modifier is not recipient");
                    check(e.value("mode").equals(mode),"mode literal");check(e.completionScope.equals("full_course"),"past complete scope");
                    check(e.completedPersonalTeaching(),"past completed own teaching");
                    boolean onsite=Set.of("现场面授","面对面","线下面授").contains(mode);
                    check(TeachingEvents.supportsFeature(e.record(),"面对面授课形式",List.of())==onsite,"event-scoped onsite mode");
                    check(InstructorMode.field(mode,"面对面授课形式")==onsite,"onsite mode");
                    check(InstructorMode.field(mode,"远程授课形式")!=onsite,"remote mode");
                    check(InstructorMode.field(mode,"线上实时教学")==mode.equals("线上实时"),"recorded is not live");
                    check(admitted(topic,"在现场",s)==onsite,"actual same-course onsite assessment");spans(s);
                }
            }
            String plain="我已给带教师傅讲授《"+topic+"》，采用现场面授。";
            check(event(plain).completionScope.equals("past_teaching"),"past lecture not whole course");
            check(!admitted(topic,"在现场",plain),"full-course requirement not satisfied by unspecified completion");
            String present="我给带教师傅完整讲授《"+topic+"》，采用现场面授。";
            check(!event(present).completedPersonalTeaching(),"complete adjective alone is not past");check(!admitted(topic,"在现场",present),"no invented history");
            String before="我已完整给带教师傅讲授《"+topic+"》，采用现场面授。";
            check(event(before).value("audience").equals("带教师傅"),"pre-recipient modifier");check(admitted(topic,"在现场",before),"pre-recipient complete modifier");spans(before);
            for(String qualifier:List.of("不完整","未完整","不够完整","未必完整","部分完整","基本完整","大致完整","可能完整","尽量完整","安排独立")){
                String s="我已给带教师傅"+qualifier+"讲授《"+topic+"》，采用现场面授。";
                check(!admitted(topic,"在现场",s),"qualified modifier not silently satisfied: "+qualifier);spans(s);
            }
            String lead="我已给带教师傅完整讲授《"+topic+"》。";
            for(String learner:List.of("学员提交练习","只有学员在线上观看","学员完成作业")){
                String implicit=lead+learner+"，采用现场面授。";
                check(!admitted(topic,"在现场",implicit),"learner antecedent cannot silently become instructor mode");spans(implicit);
                String explicit=lead+learner+"，授课形式为现场面授。";
                check(admitted(topic,"在现场",explicit),"explicit course delivery label after learner context");spans(explicit);
            }
            for(String s:List.of(lead+"计划采用现场面授。",lead+"采用现场面授和在线录屏。",lead+"学员采用现场面授。",lead+"同事采用现场面授。",lead+"“采用现场面授”。",lead+"采用现场面授。采用在线录屏。",lead+"\n\n采用现场面授。",lead+"我已主讲《另一课程》。采用现场面授。")){
                check(!admitted(topic,"在现场",s),"mode context not borrowed: "+s);spans(s);
            }
            check(!admitted(topic,"在现场","同事已给带教师傅完整讲授《"+topic+"》，采用现场面授。"),"other actor not reassigned");
            check(!admitted(topic,"在现场","我计划给带教师傅完整讲授《"+topic+"》，采用现场面授。"),"plan remains not completed");
            check(!admitted(topic,"在现场","采用现场面授。"),"mode alone creates no course/history");
            for(String end:List.of("。","","。\r\n"))spans(lead+"采用现场面授"+end);
        }
        for(String prefix:List.of("采用","使用","授课形式为","教学形式为","形式为")){
            String s=prefix+"在线录屏教学";InstructorMode.Continuation c=InstructorMode.continuation(s);
            check(c!=null&&c.mode().equals("在线录屏"),"bounded continuation");check(c.mode().equals(s.substring(c.start(),c.end())),"literal continuation offsets");
        }
        for(String s:List.of("计划采用现场面授","并非采用现场面授","采用现场面授视频","采用在线录屏或现场面授","采用未知方式","学员采用现场面授","同事采用现场面授"))check(InstructorMode.continuation(s)==null,"unknown/qualified continuation");
        System.out.println("Active teaching fields checks: "+checks);
    }
}
