package com.training;

import java.time.*;
import java.util.*;
import java.util.regex.*;

/** One complete literal clause: a named course, a personal passive predicate,
 * and an explicit past marker or an entirely elapsed date period. */
final class NamedPassiveTeaching {
    private static final String MODE="面对面|线下|面授|现场|线上|远程|直播|录播";
    private static final Pattern CLAUSE=Pattern.compile(
        "^(?<date>20[0-9]{2}年(?:[0-9]{1,2}月(?:[0-9]{1,2}日)?)?)?"+
        "(?:《(?<quoted>[^《》\\r\\n，,。；;！？!?]{2,80})》(?<quotedMode>"+MODE+")?(?:课程|课|工作坊|培训)?|"+
        "(?<bare>[^《》\\r\\n，,。；;！？!?：:]{2,80}?)(?:课程|课|工作坊|培训))"+
        "(?<before>已经|已|曾经|曾)?由(?<actor>本人|我|该讲师|该教师|该老师)"+
        "(?<mod>(?:(?:已经|已|曾经|曾|实际|亲自|独立|全程)){0,4})"+
        "(?:(?:给|为|面向)(?<audience>[^《》\\r\\n，,。；;！？!?：:]{1,40}?))?"+
        "(?<delivery>(?:(?:已经|已|曾经|曾|实际|亲自|独立|全程|完整|从头到尾)){0,4})"+
        "(?<action>主讲|讲授|讲完|完成(?:了)?(?:全课|全部课程)?(?:的)?(?:教学|讲授|授课))(?<aspect>过|了)?$");
    record Fact(String course,int courseStart,int courseEnd,String actor,int actorStart,int actorEnd,
                String mode,int modeStart,int modeEnd,String date,int dateStart,int dateEnd,
                String audience,int audienceStart,int audienceEnd,
                boolean history,boolean finished,boolean independent,List<String> issues) {}
    record Section(String text,int start,int end) {}
    static Section excludedSection(String source,int at) {
        Section excluded=null;Matcher lines=Pattern.compile("[^\\r\\n]+").matcher(source.substring(0,at));
        while(lines.find()) {
            String line=lines.group().strip();
            boolean team=line.matches("(?:团队|机构|公司)(?:案例|授课案例|授课经历|教学成果)[:：]?");
            if(team||EvidenceSections.boundary(line)&&line.matches("(?:团队.*|机构.*|公司.*|未.*|尚未.*|仅参训.*|参训.*|参加过.*|助教.*|筹备.*|未来.*|计划.*)")) {
                int start=lines.start()+lines.group().indexOf(line);excluded=new Section(line,start,start+line.length());
            }else if(line.matches("(?:个人介绍|主讲课程|精品课程|课程列表|擅长领域|授课专长|授课领域|主讲方向|授课风采|教学经历|实际授课记录|个人职责|个人案例|本人经历)[:：]?"))excluded=null;
        }
        return excluded;
    }
    static Fact parse(String clause){return parse(clause,LocalDate.now(ZoneId.of("Asia/Shanghai")));}
    static Fact parse(String clause,LocalDate today){
        Matcher m=CLAUSE.matcher(clause);if(!m.matches())return null;
        String key=m.group("quoted")!=null?"quoted":"bare",course=m.group(key);
        if(course.isBlank()||course.matches("^(?:计划|拟|预计|预定|准备|即将|将要|下周|下月|明年|明天|假设|假如|如果|转述|据说|引用|摘录|模板|仅参训|未授课|未讲授).*"))return null;
        if(key.equals("bare")&&(NarrativeTeachingEvents.courseReference(course)||
            course.matches("(?:这门|这堂|这一|这次|本次|本|该)(?:面授|线上|远程|线下)?")))return null;
        // Do not recover from a partial range/date parse by calling its tail a course.
        if(key.equals("bare")&&course.matches("^(?:至|到|[-—－])?20[0-9]{2}年.*"))return null;
        String date=Objects.toString(m.group("date"),"");LocalDate start=null,end=null;
        if(!date.isEmpty()) {
            Matcher d=Pattern.compile("(20[0-9]{2})年(?:([0-9]{1,2})月(?:([0-9]{1,2})日)?)?").matcher(date);d.matches();
            try {
                int year=Integer.parseInt(d.group(1));
                if(d.group(2)==null){start=LocalDate.of(year,1,1);end=LocalDate.of(year,12,31);}
                else if(d.group(3)==null){YearMonth month=YearMonth.of(year,Integer.parseInt(d.group(2)));start=month.atDay(1);end=month.atEndOfMonth();}
                else{start=LocalDate.of(year,Integer.parseInt(d.group(2)),Integer.parseInt(d.group(3)));end=start;}
            }catch(DateTimeException ex){return null;}
        }
        String recipient=Objects.toString(m.group("audience"),"");
        // Recipients may themselves be teachers. Reject intervening actions or
        // qualifications, not the learner group's professional title.
        if(!recipient.isEmpty()&&(recipient.isBlank()||Pattern.compile("邀请|安排|委托|联络|协调|协助|审核|审批|请|让|由|拟|计划|准备|尚未|未曾|并非|不是|而非|没有|否认|主讲|讲授|授课|讲完|完成|作为|担任|代为").matcher(recipient).find()))return null;
        String action=m.group("action"),mod=m.group("mod")+m.group("delivery");
        boolean finished=action.equals("讲完")||action.startsWith("完成");
        boolean explicit=finished||m.group("before")!=null||m.group("aspect")!=null||mod.matches(".*(?:已|曾).*");
        boolean future=start!=null&&start.isAfter(today);
        boolean history=!future&&(explicit||end!=null&&end.isBefore(today));
        List<String> issues=future?List.of("future_dated_passive_not_history"):List.of();
        String mode=Objects.toString(m.group("quotedMode"),"");int modeStart=mode.isEmpty()?-1:m.start("quotedMode");
        if(key.equals("bare")) {
            // A suffix immediately qualifying 课程 is a mode cue. Keep the
            // complete literal course span; never strip words out of a title.
            Matcher form=Pattern.compile("(?:"+MODE+")$").matcher(course);
            if(form.find()){mode=form.group();modeStart=m.start(key)+form.start();}
        }
        return new Fact(course,m.start(key),m.end(key),m.group("actor"),m.start("actor"),m.end("actor"),
            mode,modeStart,modeStart<0?-1:modeStart+mode.length(),date,date.isEmpty()?-1:m.start("date"),date.isEmpty()?-1:m.end("date"),
            recipient,recipient.isEmpty()?-1:m.start("audience"),recipient.isEmpty()?-1:m.end("audience"),
            history,finished&&history,mod.contains("独立"),issues);
    }
    private NamedPassiveTeaching() {}
}
