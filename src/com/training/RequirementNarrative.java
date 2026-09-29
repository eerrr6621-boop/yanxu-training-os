package com.training;

import java.util.*;
import java.util.regex.*;

/** Grammar-level demand roles. Course and audience are literal spans, never
 * domain labels inferred from hiring language or from a teacher's biography. */
final class RequirementNarrative {
    record Range(int start,int end,String text) {}
    record Declaration(Range course,Range audience,Range mode,boolean stageOnly) {}
    record History(List<Range> references,List<Range> audienceReferences,boolean independent,Range mode) {
        History(List<Range> references,List<Range> audienceReferences,boolean independent){this(references,audienceReferences,independent,null);}
    }
    private static final Pattern HIRING=Pattern.compile("^(?:(?:本次|这次)?(?:我们|客户|本单位|[\\p{IsHan}]{1,16}(?:站|馆|中心|公司|部门|机构|银行|学校|团队)))?(?:给|为|面向)(?<audience>[^《》，,。；;：:]{1,30}?)(?:找|寻找|招聘)(?:一位|一名)?《(?<course>[^《》]{2,80})》(?:的)?(?:老师|讲师|教师)$");
    private static final Pattern ATTACHED_HISTORY=Pattern.compile("^(?:须|需|必须|要求|需要)(?:有|具有|具备)(?:给|为|面向)(?<audience>[^《》，,。；;：:]{2,30}?)(?<mode>现场面授|到场面授|面对面|线下面授|远程直播|线上直播)(?<independent>独立)?(?:完整)?完成(?<course>该课程|这门课程|该课|这门课)(?:的)?(?:教学|授课)?(?:经历|记录)$");
    private static final String MODE="(?:面授|线下|线上|远程|面对面)";
    private static final String REF="(?:这(?:一)?(?:门|堂)(?:"+MODE+")?课(?:程)?|该(?:门|堂)?课(?:程)?|同(?:一)?主题(?:课堂|课程)|同一(?:门)?课程|同一内容)";
    private static final Pattern COURSE_REF=Pattern.compile(REF);
    private static final Pattern DECLARATION=Pattern.compile(
        "^([^《》，,。；;：:]{0,30}?)(?:给|为|面向)([^《》，,。；;：:]{1,30}?)(补|讲授|开设|安排|开展|上|讲|教)(?:一(?:堂|门|场|次))?(《[^《》]{2,80}》|[^《》]{2,80}?)(课(?:程)?|的(?:"+MODE+")?(?:老师|讲师))$");
    private static final Pattern AUD_REF=Pattern.compile("(?:为|给|面向)(这类新人|这批新人|这类学员|这批学员|这些学员|上述人群|上述对象|该对象)");
    private static final Pattern INTENT_PREFIX=Pattern.compile("^(?:本次|这次)?(?:(?:我们|客户|本单位)?招聘(?:的)?(?:是)?|(?:(?:我们|客户|本单位|[\\p{IsHan}]{1,16}(?:站|中心|公司|部门|机构|银行|学校|团队)))?(?:要|需|需要|计划|准备|希望|寻找|请|找|想))?$");
    private static final Pattern REQUESTED_TOPIC=Pattern.compile(
        "^((?:我们|客户|本单位|[\\p{IsHan}]{1,16}(?:站|馆|场|中心|公司|部门|机构|银行|学校|团队))?(?:本次|这次|下一次|下次|接下来)?(?:想|希望|计划|准备|需要|要))(?:讲授|讲|教)(《[^《》]{2,80}》|[^《》，,。；;：:]{2,80}?)(课程|课)?$");
    private static final Pattern OBJECT_CONTROL=Pattern.compile(
        "但是|然而|而且|并且|同时|否则|如果|假如|除非|但|且|只(?:能|要|接受|考虑|限)|仅(?:能|接受|限)|唯一|限于");
    // A positive coordination prefix belongs to the full closed predicate.
    // Never strip a conditional, alternative, negation, or unknown suffix.
    private static final String COORDINATION="(?:并且|并|且|同时)?";
    private static final String LEAD="(?:请|找|希望|需要|要求|必须|需|须|要|有|提供|具备|以前|以往|过去|既往|确实|曾经|曾|已经|已|本人|自己|他本人|实际|完整|独立|亲自|@AUD@)";
    private static final Pattern HISTORY=Pattern.compile("^"+COORDINATION+LEAD+"{1,20}(上完过|讲完过|教完过|带完|讲完|教完|完成)@COURSE@(?:的)?(?:课堂|教学|授课)?(?:的)?(?:既往|以前|实际|已完成)?(?:经历|记录)?(?:、(?:整堂|全程|全部|整门课)由(?:自己|本人)(?:独立)?(?:讲授|讲))?(?:的(?:老师|讲师))?$");
    private static final Pattern POST_OBJECT_HISTORY=Pattern.compile(
        "^"+COORDINATION+"(?:请|希望|需要|要求|必须|须|需|老师|讲师|教师|已经|曾经|过去|以前|以往|本人|他本人|自己|亲自|独立|确实|由|要|已|曾){1,20}把@COURSE@(?:教完过|讲完过|上完过|讲完|教完)(?:的)?(?:课堂|教学|授课)?(?:经历|记录)?(?:的(?:老师|讲师))?$");
    private static Range range(String text,int start,int end) {return new Range(start,end,text.substring(start,end));}
    static Declaration declaration(String text) {
        Matcher hiring=HIRING.matcher(text);
        if(hiring.matches()) {
            String outside=text.substring(0,hiring.start("course"))+text.substring(hiring.end("course"));
            String receiver=hiring.group("audience");
            if(!outside.matches(".*(?:如果|假如|不要|无需|不能|不得|没有|尚未|或者|或是|要求|必须|仅|只|[()（）]).*")&&
                    !receiver.matches(".*(?:邀请|安排|计划|让|讲授|主讲|授课|完成|至少|每场|每次).*") )
                return new Declaration(range(text,hiring.start("course"),hiring.end("course")),
                    range(text,hiring.start("audience"),hiring.end("audience")),null,false);
        }
        Matcher m=DECLARATION.matcher(text);if(!m.matches())return requestedTopic(text);
        String prefix=m.group(1);
        if(!INTENT_PREFIX.matcher(prefix).matches())return null;
        if(prefix.matches(".*(?:不要|不需要|无需|不得|不能|未|没有).*"))return null;
        // Administrative actions are not necessarily teaching. A teacher as
        // the requested actor cannot turn an arbitrary task into a course.
        if(Set.of("安排","开展","开设").contains(m.group(3))
            &&!m.group(5).matches("课程?")
            &&!text.substring(m.end(3),m.start(4)).matches("一(?:堂|门)"))return null;
        String audience=m.group(2),course=m.group(4);
        boolean stageOnly=!course.startsWith("《")&&course.matches("岗前|上岗前|入职|入职前|上岗|岗中|复训");
        // A recipient containing another action or an unresolved alternative
        // is not a uniquely bound audience. Modifiers are retained, not erased.
        if(audience.matches(".*(?:邀请|安排|计划|让|讲授|主讲|授课|或者|或是).*"))return null;
        int start=m.start(4),end=m.end(4);
        if(course.startsWith("《")){start++;end--;course=course.substring(1,course.length()-1);}
        if(COURSE_REF.matcher(course).find()||course.matches(".*(?:必须|要求|需要|希望|不得|不能|无需|下月|明年|至少|每场|每次|或者|或是|[：:<>≥≤]).*"))return null;
        Matcher mode=Pattern.compile(MODE).matcher(m.group(5));Range modeRange=null;
        if(mode.find())modeRange=range(text,m.start(5)+mode.start(),m.start(5)+mode.end());
        return new Declaration(range(text,start,end),range(text,m.start(2),m.end(2)),modeRange,stageOnly);
    }
    private static Declaration requestedTopic(String text) {
        Matcher m=REQUESTED_TOPIC.matcher(text);if(!m.matches())return null;
        // Only an explicit teaching intention provides this object. Do not
        // absorb a conditional/negative requester or manufacture an audience.
        if(m.group(1).matches(".*(?:如果|假如|只有|除非|必须|不得|不能|不要|没有|尚未).*"))return null;
        String course=m.group(2);int start=m.start(2),end=m.end(2);
        // The grammatical 课 suffix may be part of a demonstrative reference;
        // inspect it before removing the generic course noun from an anchor.
        if(COURSE_REF.matcher(course+Objects.toString(m.group(3),"")).find())return null;
        boolean titled=course.startsWith("《");
        if(titled){start++;end--;course=course.substring(1,course.length()-1);}
        // An unquoted object cannot swallow a restriction as part of a course
        // name. Keep the whole original clause unresolved; never trim away the
        // condition and then claim the remainder is fully parsed.
        if(!titled&&(OBJECT_CONTROL.matcher(course).find()||RequirementConstraints.COMPARISON.matcher(course).find()))return null;
        if(COURSE_REF.matcher(course).find()||course.matches(".*(?:必须|要求|需要|希望|不得|不能|无需|老师|讲师|下月|明年|至少|每场|每次|或者|或是|[：:<>≥≤]).*"))return null;
        boolean stageOnly=!titled&&course.matches("岗前|上岗前|入职|入职前|上岗|岗中|复训");
        return new Declaration(range(text,start,end),null,null,stageOnly);
    }
    static History history(String text,String singleAudience) {
        Matcher attached=ATTACHED_HISTORY.matcher(text);
        if(attached.matches()&&singleAudience!=null) {
            String receiver=attached.group("audience"),core=receiver.replaceFirst("^同类","");
            boolean bound=receiver.equals(singleAudience)||receiver.startsWith("同类")&&core.length()>=2&&singleAudience.endsWith(core);
            if(bound&&!singleAudience.matches(".*(?:或者|或是|以及|、|和|与|[()（）]).*")&&
                    !receiver.matches(".*(?:邀请|安排|计划|让|由|主讲|讲授|授课|未|不|没有|仅|只|必须|要求|至少|每场|每次|[()（）]).*"))
                return new History(List.of(range(text,attached.start("course"),attached.end("course"))),
                    List.of(range(text,attached.start("audience"),attached.end("audience"))),attached.group("independent")!=null,
                    range(text,attached.start("mode"),attached.end("mode")));
        }
        Matcher reference=COURSE_REF.matcher(text);List<Range> refs=new ArrayList<>();
        while(reference.find())refs.add(range(text,reference.start(),reference.end()));
        if(refs.size()!=1)return null;
        Range course=refs.get(0);String skeleton=text.substring(0,course.start())+"@COURSE@"+text.substring(course.end());
        Matcher audiences=AUD_REF.matcher(text);List<Range> audienceRefs=new ArrayList<>();
        while(audiences.find()) {
            if(singleAudience==null)return null;
            if(audiences.group().contains("新人")&&!singleAudience.matches(".*(?:入职|新人|新员工).*"))return null;
            audienceRefs.add(range(text,audiences.start(),audiences.end()));
        }
        skeleton=AUD_REF.matcher(skeleton).replaceAll("@AUD@");
        boolean predicate=HISTORY.matcher(skeleton).matches()||POST_OBJECT_HISTORY.matcher(skeleton).matches();
        if(!predicate||!skeleton.matches(".*(?:以前|以往|过去|既往|曾|已|过|经历|记录).*"))return null;
        boolean independent=skeleton.contains("独立")||skeleton.matches(".*(?:整堂|全程|全部|整门课)由(?:自己|本人)(?:讲授|讲).*" )
            ||skeleton.matches(".*(?:自己|本人)完整(?:带完|讲完|教完).*" );
        return new History(List.copyOf(refs),List.copyOf(audienceRefs),independent);
    }
    /** Remove recognised request/recipient roles only for topic vocabulary
     * lookup; the original demand and every constraint remain unchanged. */
    static String topicView(String text) {
        char[] view=text.toCharArray();Matcher clauses=Pattern.compile("[^，,。；;\\r\\n]+").matcher(text);
        while(clauses.find()) {
            String raw=clauses.group();int trim=raw.length()-raw.stripLeading().length();String clause=raw.strip();
            Declaration d=declaration(clause);if(d==null)continue;
            int start=clauses.start()+trim;Arrays.fill(view,start,start+clause.length(),' ');
            if(!d.stageOnly())for(int i=d.course().start();i<d.course().end();i++)view[start+i]=clause.charAt(i);
        }
        return new String(view);
    }
    private RequirementNarrative() {}
}
