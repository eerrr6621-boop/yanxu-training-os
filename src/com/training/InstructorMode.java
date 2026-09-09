package com.training;

import java.util.*;
import java.util.regex.*;

/** Literal delivery-mode attribution. Learner location/homework is not the
 * instructor's mode. This neither creates personal history nor joins events. */
final class InstructorMode {
    private static final int ONSITE=1,REMOTE=2,LIVE=4;
    private static final Pattern MODE=Pattern.compile("面对面|线下|面授|现场(?:完成过|完成)?(?:教学|授课|主讲)|现场面授|在线录屏|录屏|线上|远程|直播|录播|在线教学");
    private static final String ACTOR="(?:本人|我|该讲师|该教师|主讲教师|授课教师|讲师|教师)";
    private static final Pattern DECLARATION=Pattern.compile("^(?:并且|并|且)?(?:"+ACTOR+")?(?:每场|每次|全程)?(?:授课|教学)?(?:采用|使用|形式为|形式是|方式为|方式是|形式[:：])?(?:中文|汉语|英文|英语)?(?:到场面授|面对面|线下|面授|现场|线上|远程|直播|录播|录屏|实时|在线)+(?:教学|授课|讲授|主讲)?(?:形式)?$");
    private static final Pattern UNSAFE=Pattern.compile("计划|拟|将|可以|可提供|愿意|能够|可采用|尚未|未曾|未实际|假设|假如|如果|示例|样例|模板|据说|转述|表示|声称|否认|没有|不是|并非|而非|不含|不采用|取消|待核|待定|不确定|可能");
    private static final Pattern PERSONAL_MODE=Pattern.compile("^(?<actor>本人|我|该讲师|该教师)"+
        "(?:(?:已经|已|曾经|曾|实际|亲自|全程)){0,3}(?:作为(?:主讲|授课)(?:教师|讲师|老师))?"+
        "(?:(?:在(?:教室|课堂|培训现场))?(?<onsite>到场面授|现场面授|面授|面对面|线下)(?:教学|授课|讲授)?|"+
        "通过(?<connection>远程)连线(?:授课|教学)|(?<online>远程直播|远程实时|远程|线上实时|线上直播|线上录播|线上|直播|录播|在线录屏)(?:授课|教学|讲授))$");
    private static final String LITERAL_MODE="到场面授|现场面授|面对面|线下面授|面授|线下|远程直播|远程实时|远程|线上实时|线上直播|线上录播|线上|直播|录播|在线录屏";
    private static final Pattern CONTINUATION=Pattern.compile("^(?:采用|使用|(?:授课|教学)?(?:形式|方式)(?:为|是))(?<mode>"+LITERAL_MODE+")(?:教学|授课|讲授)?$");
    record SourceDeclaration(String actor,int actorStart,int actorEnd,String mode,int modeStart,int modeEnd) {}
    record Continuation(String mode,int start,int end) {}
    /** Only the caller's existing event supplies ownership. This literal
     * continuation never creates a course, actor, history or live-video claim. */
    static Continuation continuation(String clause) {
        Matcher m=CONTINUATION.matcher(clause);
        if(!m.matches()||UNSAFE.matcher(clause).find()||ProfessionalEvidence.negative(clause))return null;
        return new Continuation(m.group("mode"),m.start("mode"),m.end("mode"));
    }
    /** Mode context for an already established event. This cannot create a
     * course, completed history or a lead role on its own. */
    static SourceDeclaration sourceDeclaration(String clause) {
        Matcher m=PERSONAL_MODE.matcher(clause);
        if(!m.matches()||UNSAFE.matcher(clause).find()||ProfessionalEvidence.negative(clause))return null;
        String group=m.group("onsite")!=null?"onsite":m.group("connection")!=null?"connection":"online";
        return new SourceDeclaration(m.group("actor"),m.start("actor"),m.end("actor"),m.group(group),m.start(group),m.end(group));
    }
    static boolean learnerContext(String clause) {return learnerActivity(clause)&&!UNSAFE.matcher(clause).find();}
    record Clause(String literal,String visible,int modes,boolean owned,boolean irrelevant) {}
    static boolean applies(String label) {return label.equals("面对面授课形式")||label.equals("远程授课形式")||label.equals("线上实时教学");}
    static boolean field(String value,String label) {return supported(value,label,c->true);}
    static boolean prose(String text,String label,Map<String,List<String>> facets) {
        return supported(text,label,c->RequirementConstraints.featureScoped(text,facets,List.of(c.literal)));
    }
    private static boolean supported(String text,String label,java.util.function.Predicate<Clause> scoped) {
        String visible=outsideQuotes(text);if(visible==null)return false;
        List<Clause> clauses=new ArrayList<>();Matcher split=Pattern.compile("[^，,。；;！？!?\\r\\n]+").matcher(visible);
        int all=0;
        while(split.find()) {
            String part=split.group().strip();int modes=modes(part);if(modes==0)continue;
            boolean irrelevant=learnerActivity(part);
            boolean owned=!irrelevant&&owned(part);
            // A mode-bearing qualification we cannot attribute stays pending.
            // Do not silently erase a possible correction to the teacher mode.
            if(!irrelevant&&!owned)return false;
            if(owned)all|=modes;
            clauses.add(new Clause(text.substring(split.start(),split.end()).strip(),part,modes,owned,irrelevant));
        }
        if((all&ONSITE)!=0&&(all&REMOTE)!=0)return false;
        int requested=label.equals("面对面授课形式")?ONSITE:label.equals("线上实时教学")?LIVE:REMOTE;
        for(Clause clause:clauses)if(clause.owned&&(clause.modes&requested)!=0&&scoped.test(clause))return true;
        return false;
    }
    private static int modes(String text) {
        int found=0;Matcher matcher=MODE.matcher(text);
        while(matcher.find()) {String token=matcher.group();found|=token.matches("面对面|线下|面授|现场.*")?ONSITE:REMOTE;if(token.equals("直播")||(token.equals("线上")||token.equals("远程"))&&text.contains("实时"))found|=LIVE;}
        return found;
    }
    private static boolean learnerActivity(String clause) {
        return clause.matches("^(?:只有|仅|全部|所有)?(?:学员|学生|参训人员|学习者)(?:们)?.*")&&
            clause.matches(".*(?:练习|作业|提交|讨论|观看|参训|学习|演练|签到).*")&&
            !clause.matches(".*(?:讲师|教师|主讲|授课|讲授|教学|表示|转述|称|听说|要求|安排).*");
    }
    private static boolean owned(String clause) {
        if(UNSAFE.matcher(clause).find()||ProfessionalEvidence.negative(clause))return false;
        clause=clause.replaceFirst("^(?:在)?20[0-9]{2}年(?:[0-9]{1,2}月(?:[0-9]{1,2}日)?)?(?:期间)?","");
        if(DECLARATION.matcher(clause).matches())return true;
        if(clause.matches("^(?:并且|并|且)?"+ACTOR+"(?:已|已经|曾|实际|负责|仅|只|作为)*(?:学习|参训|旁听|观摩|组织|安排|记录|整理|协助|辅助|配合|助教|推荐|建议|代为).*")||
            clause.matches(".*(?:授课|教学|主讲|讲授)(?:由|交由|改由)(?:同事|助教|团队|机构).*")||
            clause.matches(".*(?:同事|助教|团队|机构)(?:已|已经|实际|负责)*(?:主讲|授课|讲授).*"))return false;
        if(clause.matches("^.{1,90}(?:课程|课堂|工作坊|教学|授课)(?:已经|已|曾)?由(?:本人|我|该讲师|该教师)(?:实际|亲自|独立|全程)*(?:主讲|讲授|授课)(?:过|完成)?$")&&
            !clause.matches(".*(?:学员|学生|参训人员|学习者|同事|助教|团队|机构)(?:在线上|在线下|现场|远程|线上|线下).*"))return true;
        if(!clause.matches("^(?:并且|并|且)?"+ACTOR+".*")||!clause.matches(".*(?:主讲|讲授|讲完|授课|教学|工作坊).*")||
            clause.matches(".*(?:学员|学生|参训人员|学习者).*(?:面对面|线下|面授|现场|线上|远程|直播|录播|在线).*(?:练习|作业|提交|讨论|观看|参训|学习|演练|签到).*"))return false;
        return true;
    }
    /** Quoted titles/instructions cannot supply or contradict delivery mode. */
    private static String outsideQuotes(String text) {
        StringBuilder out=new StringBuilder(text);Deque<Character> stack=new ArrayDeque<>();
        for(int i=0;i<text.length();i++) {
            char ch=text.charAt(i);
            if(ch=='《'||ch=='“'||ch=='"'&&(stack.isEmpty()||stack.peek()!='"')){stack.push(ch=='《'?'》':ch=='“'?'”':'"');out.setCharAt(i,' ');}
            else if(ch=='》'||ch=='”'||ch=='"'){if(stack.isEmpty()||stack.pop()!=ch)return null;out.setCharAt(i,' ');}
            else if(!stack.isEmpty()&&ch!='\n'&&ch!='\r')out.setCharAt(i,' ');
        }
        return stack.isEmpty()?out.toString():null;
    }
    private InstructorMode() {}
}
