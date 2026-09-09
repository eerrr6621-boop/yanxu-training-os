package com.training;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/** Contiguous source/context units. Self-reported teaching is not verified history. */
final class ProfessionalEvidence {
    private static final Pattern NEGATIVE = Pattern.compile("不擅长|不包含|不需要|无需|不涉及|不具备|不是|并非|不熟悉|不了解|没有|暂无|尚无|缺乏|缺少|尚未|未曾|从未|未担任|未涉及|未具备|未授课|未从事|未提供|未主讲|未讲授|未亲自(?:主讲|讲授|授课)|未实际(?:主讲|讲授|授课)|未承担|未实施|未开展|仅参加|仅参训|仅听过|待核实|待确认|待核验|[（(]筹[）)]|筹备中|(?:课程|课堂|练习)(?:不要求|不安排|不开展|不进行)");
    private static final Pattern NO_EXPERIENCE = Pattern.compile("无(?:相关|任何|实际|教学|授课|从业|主讲|行业|项目|工作|银行|金融|客户|课程|管理|培训){1,6}(?:经验|经历|背景)");
    private static final Pattern PERSONAL = Pattern.compile("(?:性别|年龄|出生日期|出生年月|民族|婚姻状况|宗教信仰|身份证|家庭住址|联系电话|手机号码|电子邮箱)\\s*[:：]");
    private static final Pattern PARTICIPATION = Pattern.compile("(?:参加|参与|参训|学习|听课|结业).{0,50}(?:课程|培训|认证|证书)");
    private static final Pattern EXCLUDED_HEADING = Pattern.compile("(?:未授课课程|未讲授课程|尚未开设课程|仅参训课程|参训经历|参加过的培训|助教经历|筹备课程|未来规划|计划课程)[:：]?");
    private static final Pattern POSITIVE_HEADING = Pattern.compile("(?:个人介绍|主讲课程|精品课程|课程列表|擅长领域|授课专长|授课领域|主讲方向|授课风采|教学经历|实际授课记录)[:：]?");
    private static final Pattern SUPPORT_ONLY = Pattern.compile("(?:本人|我)?(?:仅|只|主要)(?:负责|承担|参与|从事).{0,50}(?:会务|报名|签到|会场|资料|教材|助教|统筹|课程库)|(?:担任|作为).{0,16}助教|(?:拟|计划)(?:开设|主讲|授课)|正在筹备");
    private static final Pattern OWN_TEACHING = Pattern.compile("(?:本人|我)(?:独立|实际|已经|曾经|曾|亲自|参与|负责|于20\\d{2}年)?(?:主讲|讲授|授课|讲完|完成.{0,12}(?:教学|授课|讲授))|^(?:实际授课记录[:：]?)?(?:20\\d{2}年.{0,24})?(?:曾|已经|实际|累计|持续).{0,24}(?:主讲|讲授|授课)|^(?:主讲过|讲授过|独立讲授)");
    private static final Pattern PERSONAL_EVENT=Pattern.compile("(?:本人|我|该讲师|该教师|该老师|同一讲师)((?:已经|已|曾经|曾|实际|分别|先|独立|亲自){0,3})(?:(?:为|给|向|面向)([^。；;，,！？!?\\r\\n]{1,30}?))?(?:独立|亲自)?(主讲|讲授|授课|讲完|完成(?:了)?[^。；;，,！？!?\\r\\n]{0,40}?(?:教学|授课|讲授))");
    private static final Pattern EVENT_OBJECT_RISK=Pattern.compile("参加|参训|旁听|准备|安排|筹备|报名|资格|认证|编写|审核|签收|登记|联络|协助|邀请|材料|申请|计划|拟|助教|助理|讲义|课件|教具|教材|老师|讲师|教师|教员|专家|他人|其他人|同事|未|不|没有");
    private static final Pattern EVENT_TAIL_RISK=Pattern.compile("^(?:的)?(?:报名|登记|准备|计划|认证|资格|证书|方案|材料|申请|审批|老师|讲师|教师|人选|名单|讲义|课件|教材|教具|助教|助理|记录|总结|报告|视频|档案)");
    private static final Pattern REVERSE_PERSONAL=Pattern.compile("(?:独立|实际|亲自)?主讲(?:老师|讲师|教师|人员|教员|人)?(?:是|为|[:：])(?:本人|我|该讲师)(?=$|[，,。；;！？!?\\r\\n])|由(?:本人|我|该讲师)(?:独立|实际|亲自)?主讲(?=$|[，,。；;！？!?\\r\\n])");
    static final Pattern ORGANIZATION_EVENT=Pattern.compile("(?:本|所在|供职)?(?:单位|部门|机构|公司|团队)(?:已|曾|累计|负责|举办|开设|开展|交付|完成)");
    private static final Pattern SUPPORT_FACT=Pattern.compile("(?:本人|我|该讲师)(?:已|已经|独立|仅|只|主要){0,3}(?:负责|承担|完成)[^。；;，,！？!?\\r\\n]{0,40}(?:讲义|教材|课件|教具|教学助理|授课助理|教学助教|安排其他人)");
    private static final Pattern REVERSE_OTHER=Pattern.compile("(?:独立|实际|亲自)?主讲(?:老师|讲师|教师|人员|教员|人)?(?:是|为|[:：])(?!(?:本人|我|该讲师)(?:$|[，,。；;！？!?\\r\\n]))[^。；;，,！？!?]{1,24}");
    private static final Pattern CONTENT_EXCLUSION=Pattern.compile("(?:本课程|该课程|这门课程|课程|课堂)?(?:不包含|不包括|不涉及)([^。；;，,！？!?\\r\\n]{2,40}?)(?:内容|模块|课程)");
    private static final Pattern RECIPIENT_RISK=Pattern.compile("邀请|安排|协助|联络|让|计划|拟|未|不|没有|承担|参与|编写|整理|由|其他|另一|主讲|讲授|授课");
    private static final Pattern OTHER_NONTEACHING_CLAUSE=Pattern.compile("(?:其他讲师|其他老师|其他教师)(?:均|都)?(?:没有|未)(?:承担|参与)(?:该课|这门课|该课程|本课|本课程)(?:的)?(?:主讲|讲授|授课)");
    private static final String CLASSROOM_ACTION="(?:写代码|编写代码|编程|配置(?:检测)?系统|安装软件|连接生产系统|操作真实设备|带电操作)";
    private static final Pattern TASK_RESTRICTION_CLAUSE=Pattern.compile("(?:本课程|该课程|这门课程|课程|课堂|本课堂|练习|本次练习)(?:均|全部)?(?:无需|不需要|不要求|不安排)(?:学员)?"+CLASSROOM_ACTION+"(?:(?:或|和|及|与|、)"+CLASSROOM_ACTION+"){0,3}");
    static final String COURSE_REFERENCE="(?:这门课(?:程)?|这堂(?:面授|线上|远程|线下)?课|该课(?:程)?|本课(?:程)?|这一主题|该主题|这一班|本班|本次课程|这次课程)";
    private static final Pattern ORGANIZATION_WORK_EXCLUSION=Pattern.compile("(?:这些|上述|该项|本项)?(?:组织|会务|后勤|保障)工作(?:均|都)?(?:不涉及|不承担)(?:课堂讲解|课堂教学|课程讲授)");
    private static final Pattern REFERENCED_PERSONAL_COMPLETION=Pattern.compile("^(?:"+COURSE_REFERENCE+"由(?:本人|我|该讲师|该教师|该老师)(?:已经|已|实际|独立|亲自|全程){0,4}讲完(?:了)?|(?:本人|我|该讲师|该教师|该老师)(?:已经|已|实际|独立|亲自|全程){0,4}(?:(?:为|给|面向)[^，,。；;]{1,40})?讲完(?:了)?"+COURSE_REFERENCE+"(?:的)?(?:全部(?:内容)?)?)$");
    private static final Pattern OTHER_ACTOR=Pattern.compile("(?:他人|别人|同事|其他讲师|其他老师)[^。；;，,！？!?]{0,32}(?:主讲|讲授|授课|讲过|课程)|由(?!(?:本人|我|该讲师))[^。；;，,！？!?]{1,12}(?:主讲|讲授|授课)|(?:特邀|外聘|外部|合作方|其他|另一位)[^。；;，,！？!?]{0,24}(?:老师|讲师|教师|专家)[^。；;，,！？!?]{0,16}(?:主讲|讲授|授课)|(?:主讲|讲授|授课)(?:老师|讲师|教师|人员)(?:是|为|[:：])(?!(?:本人|我|该讲师))[^。；;，,！？!?]{1,20}");
    private static final Pattern NAMED_TEACHER=Pattern.compile("([\\p{IsHan}]{1,4})(?:老师|讲师)[^。；;，,！？!?]{0,12}(?:主讲|讲授|授课)");
    static final Pattern ROLE_CONTEXT=Pattern.compile("(?:担任|作为|任|负责)(?:培训|教学|授课|课堂|课程)?(?:助教|助理(?!讲师|教授)|会务|学员|听众)|(?:本人|我|该讲师|该老师)[^。；;，,\\r\\n]{0,16}(?:(?:是|为)(?:培训|教学|授课|课堂|课程)?(?:助教|助理(?!讲师|教授))|旁听|参训|听课|未亲自(?:主讲|讲授|授课)|未实际(?:主讲|讲授|授课))|(?:本人角色|授课角色|项目角色|担任角色|角色)[:：](?:助教|助理|旁听|学员|会务)");
    private static final Pattern INDEPENDENT_DATED=Pattern.compile("^(?:(?:本人|我|该讲师)(?:另于|另在|于)?|另于|另在|另外于|另行于)?(20\\d{2})年(?:\\d{1,2}月)?(?:本人|我|该讲师)?(?:独立|亲自)(?:主讲|讲授|授课)");

    static String normalized(String source) { return Normalizer.normalize(source == null ? "" : source, Normalizer.Form.NFKC); }
    static boolean arrangedTeaching(String text) {
        return Pattern.compile("(?:本人|我|该讲师|该教师)(?:(?:已(?:经)?|曾(?:经)?|实际|亲自)){0,5}(?:(?:为|给|面向)[^，,。；;！？!?《》\\r\\n]{1,40})?(?:安排|委托|聘请|邀请|联络|协调|请人)(?:他人|别人|同事|讲师|老师|专家)?(?:独立)?(?:主讲|讲授|授课)").matcher(text).find();
    }
    static boolean negative(String text) { text=polarityView(text);return NEGATIVE.matcher(text).find() || NO_EXPERIENCE.matcher(text).find() || declarationQualification(text) || text.matches("(?s).*(?:不代表|不表示).{0,16}(?:本人|个人|我).{0,16}(?:授课|教学|经历).*" ); }
    static boolean completedPersonalEvent(String text) {
        java.util.regex.Matcher event=PERSONAL_EVENT.matcher(text);
        while(event.find()) {
            if(event.group(2)!=null && RECIPIENT_RISK.matcher(event.group(2)).find()) continue;
            if(event.group(3).startsWith("完成") && EVENT_OBJECT_RISK.matcher(event.group(3)).find()) continue;
            if(EVENT_TAIL_RISK.matcher(text.substring(event.end())).find()) continue;
            if(event.group(1).matches(".*(?:已|曾|实际).*") || event.group(3).matches(".*(?:完成|讲完).*")) return true;
        }
        java.util.regex.Matcher reverse=REVERSE_PERSONAL.matcher(text);
        while(reverse.find()) {
            String before=text.substring(0,reverse.start()).replaceFirst("[，,。；;！？!?\\r\\n ]+$","");
            String[] clauses=before.split("[，,。；;！？!?\\r\\n]",-1);String previous=clauses[clauses.length-1];
            if(previous.matches("(?s).*(?:(?:已|已经)(?:结课|讲完)|(?:课堂|课程)(?:已|已经)?(?:结课|结束))$")) return true;
        }
        return referencedPersonalCompletion(text);
    }
    static boolean organizationWorkExclusion(String text) {return ORGANIZATION_WORK_EXCLUSION.matcher(text).matches();}
    static boolean declarationQualification(String text) {
        // Modality and reported authorship qualify an entity declaration, even
        // when its later reference has a grammatically completed verb. These
        // are discourse operators, never a list of course-domain vocabulary.
        if(!Pattern.compile("(?:开设|开办|举办)(?:了)?《|(?:学习内容|培训主题|训练内容|课程内容)(?:定为|确定为|为|是)《").matcher(text).find())return false;
        return Pattern.compile("(?:如果|假如|假使|假设|若|倘若|转述|引用|摘录|据说|原话)[^。；;！？!?\\r\\n]{0,90}《|(?:明[天日年]|下(?:周|月|年)|即将|将要|(?:将|拟|计划|打算|准备)(?=为|给|面向|开设|开办|举办))[^。；;！？!?\\r\\n]{0,70}(?:开设|开办|举办)|(?:学员|学生|对象|人员)[^。；;！？!?\\r\\n]{0,45}(?:而非|而不是|并非)|(?:为|给|面向)非[^《》。；;]{1,40}(?:开设|开办|举办)").matcher(text).find();
    }
    /** Classification predicate shared with the retrieval worker. It is not an
     * eligibility decision: full-source actor/denial checks still apply. */
    static boolean referencedPersonalCompletion(String text) {
        for(String paragraph:text.split("\\r?\\n[\\t ]*\\r?\\n")) {
            if(declarationQualification(paragraph))continue;
            Set<String> titles=new HashSet<>();java.util.regex.Matcher title=Pattern.compile("《([^《》\\r\\n]{2,90})》").matcher(paragraph);
            while(title.find())titles.add(title.group(1));if(titles.size()!=1)continue;
            boolean declared=Pattern.compile("(?:开设|开办|举办)(?:了)?《|(?:学习内容|培训主题|训练内容|课程内容)(?:定为|确定为|为|是)《").matcher(paragraph).find();
            if(!declared)continue;
            for(String clause:paragraph.split("[，,。；;！？!?\\r\\n]+"))if(REFERENCED_PERSONAL_COMPLETION.matcher(clause.strip()).matches())return true;
        }
        return false;
    }
    static boolean supportOnly(String text) {
        java.util.regex.Matcher facts=SUPPORT_FACT.matcher(text);
        while(facts.find()) if(!completedPersonalEvent(text) ||
            facts.group().matches("(?s)^(?:本人|我|该讲师)(?:已经|已)?(?:仅|只)(?:负责|承担|完成).*")) return true;
        return false;
    }
    /** Bounded exclusion clauses are omitted from positive quotes, never erased
     * from source context. Unknown references and shared title words fail closed. */
    static List<int[]> contentExclusionSpans(String text) {
        List<int[]> spans=new ArrayList<>();if(!completedPersonalEvent(normalized(text))) return spans;
        java.util.regex.Matcher clauses=Pattern.compile("[^\\r\\n，,。；;！？!?]+").matcher(text);
        while(clauses.find()) {
            String raw=clauses.group().strip();java.util.regex.Matcher exclusion=CONTENT_EXCLUSION.matcher(normalized(raw));
            if(!exclusion.matches() || exclusion.group(1).matches("(?s).*(?:上述|以上|以下|该|这|本课|所有|全部|本人|他人|主讲|讲授|授课|教学|记录|经历|未|不|没有).*")) continue;
            Set<String> titles=new LinkedHashSet<>();java.util.regex.Matcher title=Pattern.compile("《([^》\\r\\n]{2,80})》").matcher(text.substring(0,clauses.start()));
            int titleStart=-1,titleEnd=-1;
            while(title.find()) { titles.add(title.group(1));titleStart=title.start();titleEnd=title.end(); }if(titles.size()!=1) continue;
            String course=titles.iterator().next(),omitted=exclusion.group(1);boolean overlaps=false;
            for(int i=0;i<course.length()-1;i++) if(omitted.contains(course.substring(i,i+2))) overlaps=true;
            if(overlaps) continue;
            int sentenceStart=titleStart,sentenceEnd=titleEnd;
            while(sentenceStart>0 && "。；;\r\n".indexOf(text.charAt(sentenceStart-1))<0) sentenceStart--;
            while(sentenceEnd<clauses.start() && "。；;\r\n".indexOf(text.charAt(sentenceEnd))<0) sentenceEnd++;
            if(!completedPersonalEvent(normalized(text.substring(sentenceStart,sentenceEnd)))) continue;
            int at=clauses.start()+clauses.group().length()-clauses.group().stripLeading().length();spans.add(new int[]{at,at+raw.length()});
        }
        return spans;
    }
    /** Classification only. Source text and offsets are never rewritten. */
    static List<int[]> nonproofSpans(String text) {
        List<int[]> spans=contentExclusionSpans(text);
        if(!completedPersonalEvent(text)) return spans;
        java.util.regex.Matcher clauses=Pattern.compile("[^\\r\\n，,。；;！？!?]+").matcher(text);
        while(clauses.find()) {
            String raw=clauses.group(),clause=raw.strip();
            int paragraphStart=0,paragraphEnd=text.length();
            java.util.regex.Matcher breaks=Pattern.compile("\\r?\\n[\\t ]*\\r?\\n").matcher(text);
            while(breaks.find()){if(breaks.end()<=clauses.start())paragraphStart=breaks.end();else if(breaks.start()>=clauses.end()){paragraphEnd=breaks.start();break;}}
            boolean organization=organizationWorkExclusion(clause)&&referencedPersonalCompletion(text.substring(paragraphStart,paragraphEnd));
            if(OTHER_NONTEACHING_CLAUSE.matcher(clause).matches() || TASK_RESTRICTION_CLAUSE.matcher(clause).matches() || organization) {
                int at=clauses.start()+raw.length()-raw.stripLeading().length();spans.add(new int[]{at,at+clause.length()});
            }
        }
        spans.addAll(methodContrastSpans(text));
        spans.addAll(LearnerArtifactContext.spans(text));
        spans.sort(Comparator.comparingInt(span->span[0]));return spans;
    }
    /** A method's rejected feedback utterance is not a denial of the teacher's
     * history. Recover no actor fact from it: withhold the whole contrast from
     * positive evidence and retain the unmodified paragraph as context. */
    static List<int[]> methodContrastSpans(String text) {
        List<int[]> spans=new ArrayList<>();
        if(!text.contains("而不是")&&!text.contains("而非"))return spans;
        String quote="(?:“[^“”。；;！？!?\\r\\n]{1,24}”|「[^「」。；;！？!?\\r\\n]{1,24}」|\"[^\"。；;！？!?\\r\\n]{1,24}\")";
        Pattern contrast=Pattern.compile("而(?:不是|非)(?:仅仅|只是|仅|只)?(?:评价|说|回答|反馈)"+quote);
        Matcher clauses=Pattern.compile("[^\\r\\n，,。；;！？!?]+").matcher(text);
        while(clauses.find()) {
            String raw=clauses.group(),clause=raw.strip();if(!contrast.matcher(clause).matches())continue;
            // A quoted role/history assertion is not a classroom feedback word.
            if(Pattern.compile("本人|我|他|她|讲师|老师|教师|授课|主讲|讲授|教学|经历|记录|完成|证明|否认|否定|没有|未曾|尚未|计划|拟|模板|引用|转述").matcher(clause).find())continue;
            int at=clauses.start()+raw.length()-raw.stripLeading().length(),sentenceStart=at;
            while(sentenceStart>0 && "。；;！？!?\r\n".indexOf(text.charAt(sentenceStart-1))<0)sentenceStart--;
            String before=text.substring(sentenceStart,at).strip();
            if(!before.matches("(?s)^(?:教学|授课|课堂|培训|带教)?(?:方法|步骤|流程)(?:说明)?[^。；;！？!?\\r\\n]{2,140}[，,]$"))continue;
            if(!Pattern.compile("(?:指出|说明|解释|反馈)[^，,。；;！？!?]{2,50}").matcher(before).find()||
                Pattern.compile("本人|我|同事|他人|别人|讲师|老师|教师|没有|尚未|未曾|不是|并非|否认|否定|计划|筹备|假设|假如|如果|模板|示例|原话|转述|引用|[“”\"「」]").matcher(before).find())continue;
            int paragraphStart=0;Matcher breaks=Pattern.compile("\\r?\\n[\\t ]*\\r?\\n").matcher(text);
            while(breaks.find())if(breaks.end()<=sentenceStart)paragraphStart=breaks.end();
            String prior=text.substring(paragraphStart,sentenceStart);
            if(!completedPersonalEvent(prior)||Pattern.compile("假设|假如|如果|模板|原话|转述|引用").matcher(prior).find())continue;
            // A following comma could continue or correct the quoted assertion.
            int end=at+clause.length(),next=end;while(next<text.length()&&Character.isWhitespace(text.charAt(next)))next++;
            if(next<text.length()&&"。；;！？!?".indexOf(text.charAt(next))<0)continue;
            spans.add(new int[]{at,end});
        }
        return spans;
    }
    /** Whole method sentences are non-factual context, not new teaching events. */
    static List<int[]> methodDescriptionSpans(String text) {
        List<int[]> result=new ArrayList<>();
        for(int[] contrast:methodContrastSpans(text)) {
            int start=contrast[0];
            while(start>0&&"。；;！？!?\r\n".indexOf(text.charAt(start-1))<0)start--;
            while(start<contrast[0]&&Character.isWhitespace(text.charAt(start)))start++;
            result.add(new int[]{start,contrast[1]});
        }
        return result;
    }
    static String polarityView(String text) {
        char[] view=text.toCharArray();
        for(int[] span:nonproofSpans(text)) Arrays.fill(view,span[0],span[1],' ');
        return new String(view);
    }
    static List<String> affirmativeParts(String text) {
        List<int[]> omitted=nonproofSpans(text);
        if(omitted.isEmpty()) return List.of(text);
        List<String> parts=new ArrayList<>();int begin=0;
        omitted.add(new int[]{text.length(),text.length()});
        for(int[] span:omitted) {
            while(begin<span[0] && (Character.isWhitespace(text.charAt(begin)) || "，,。；;！？!?".indexOf(text.charAt(begin))>=0)) begin++;
            // The rejected clause's comma separator is not an unfinished
            // source assertion. Keep the retained quote as a literal range.
            int end=span[0];
            while(end>begin && (Character.isWhitespace(text.charAt(end-1))
                    || (span[0]<text.length() && "，,".indexOf(text.charAt(end-1))>=0))) end--;
            if(end-begin>=2) parts.add(text.substring(begin,end));begin=span[1];
        }
        return parts;
    }
    static boolean ownTeaching(String text) { return completedPersonalEvent(text) || OWN_TEACHING.matcher(text).find(); }
    static boolean otherActor(String text) {
        text=polarityView(text);
        if(OTHER_ACTOR.matcher(text).find() || REVERSE_OTHER.matcher(text).find()) return true;
        java.util.regex.Matcher named=NAMED_TEACHER.matcher(text);
        while(named.find()) if(!named.group(1).equals("同一") && !named.group(1).replaceFirst("(?:担任|作为|是|为)$","").matches(".*(?:该|此|本人|我)$")) return true;
        return false;
    }
    static boolean sameTeacherContextSafe(String unit,String source) {
        return !unit.contains("同一讲师") || !otherActor(source);
    }
    static boolean roleSafeAt(String source,int start,int end,int contextStart,int contextEnd) {
        java.util.regex.Matcher lines=Pattern.compile("[^\\r\\n]+").matcher(source.substring(contextStart,contextEnd));
        String unit=source.substring(start,end).strip();
        if(!sameTeacherContextSafe(unit,source)) return false;
        java.util.regex.Matcher personal=INDEPENDENT_DATED.matcher(unit);
        boolean explicit=personal.find();String year=explicit?personal.group(1):"";
        boolean newEvent=unit.matches("(?s)^(?:本人|我|该讲师)?(?:另于|另在|另外于|另行于).*" );
        while(lines.find()) {
            if(!ROLE_CONTEXT.matcher(lines.group()).find() && !supportOnly(lines.group())) continue;
            int left=contextStart+lines.start(),right=contextStart+lines.end();
            if(!explicit || left<end && right>start) return false;
            Set<String> years=new HashSet<>();java.util.regex.Matcher dates=Pattern.compile("20\\d{2}(?=年)").matcher(lines.group());
            while(dates.find()) years.add(dates.group());
            if(years.size()==1 && !years.contains(year)) continue;
            if(right<=start && newEvent && years.isEmpty()) continue;
            return false;
        }
        return true;
    }
    static boolean organizationClaim(String text) {
        return (ORGANIZATION_EVENT.matcher(text).find() || text.matches("(?s).*(?:本机构|供职机构|所在机构|本公司|本团队|团队累计|机构客户名单|公司客户名单|团队完成|(?:团队|机构|公司)(?:已|曾|取得|持有|负责|完成)).*")) && !ownTeaching(text);
    }
    static boolean personalMetadataLine(String text) {
        if(!PERSONAL.matcher(text).find() || text.matches("(?s).*(?:主讲|讲授|授课|课程|教学|培训|教授|专业|经验|经历|擅长|精通|熟练|客户|银行|机构|团队|未承担|未实施|未开展).*") || negative(text)) return false;
        for(String part:text.split("[，,。;；]+")) {
            part=part.strip();if(part.isEmpty()) continue;
            java.util.regex.Matcher field=PERSONAL.matcher(part);
            if(!field.lookingAt()) return false;
            String value=part.substring(field.end()).strip();
            if(value.isEmpty() || value.length()>80 || value.matches("(?s).*[:：\\r\\n].*")) return false;
        }
        return true;
    }
    /** A present course offer is not a completed event. This narrowly prevents
     * incidental arrangement wording in course content from consuming a later,
     * explicit personal offering clause. All denial/other-actor checks remain.
     * A pronoun must follow one unambiguous named course, never a later title. */
    static boolean personalCourseOffer(String text) {
        // This exception cannot resolve reported/hypothetical authorship. Even
        // an apparently explicit first-person quote in that context stays out.
        if(Pattern.compile("假设|假如|假使|如果|倘若|转述|引用|摘录|据说|示例|样例|范例|例句|模板|原话").matcher(text).find())return false;
        Set<String> titles=new HashSet<>();java.util.regex.Matcher names=Pattern.compile("《([^《》\\r\\n]{2,90})》").matcher(text);
        int firstEnd=-1;while(names.find()){titles.add(names.group(1));if(firstEnd<0)firstEnd=names.end();}
        if(titles.size()!=1)return false;
        java.util.regex.Matcher offer=Pattern.compile("(?:^|[。；;！？!?\\r\\n])(?:本人|我|该讲师|该教师|该老师)(?:可以|能够|可|能)提供"
            +"(这门课(?:程)?|该课(?:程)?|本课(?:程)?|《[^《》\\r\\n]{2,90}》(?:课程)?)"
            +"[，,](?:并)?亲自(?:承担)?(?:讲授|授课|主讲)(?=$|[。；;！？!?\\r\\n])").matcher(text);
        while(offer.find()){
            String object=offer.group(1);
            if(object.startsWith("《")){String title=object.substring(1,object.indexOf('》'));if(titles.contains(title))return true;}
            else if(firstEnd<=offer.start())return true;
        }
        return false;
    }
    static boolean allowed(String text) {
        return allowed(text,false);
    }
    private static boolean allowed(String text,boolean ignoreParticipation) {
        text=polarityView(text);
        if(arrangedTeaching(text))return false;
        if(text.isBlank() || negative(text) || PERSONAL.matcher(text).find() || SUPPORT_ONLY.matcher(text).find() ||
                ROLE_CONTEXT.matcher(text).find() || supportOnly(text) || otherActor(text) ||
                text.matches("(?is).*xxx.*") || text.contains("替换个人照片")) return false;
        // A dated event describes its actor, not automatically the resume owner.
        // Explicit personal attendance/support cannot borrow another teacher's verb.
        if(text.matches("(?s).*(?:本人|我)(?:仅|只|负责|参与|旁听).{0,40}(?:设备调试|旁听|资料编写|资料审核|会务).*" ) && !text.matches("(?s).*(?:本人|我)(?:独立|实际|亲自|曾|已经)?(?:主讲|讲授|授课).*")) return false;
        if(text.matches("(?s).*(?:外部|外聘|其他|另一位|合作方)(?:讲师|老师).{0,24}(?:主讲|讲授|授课).*")) return false;
        if(text.matches("(?s).*(?:本人|我)(?:仅|只)?(?:负责|参与|协助|旁听).*") &&
                text.matches("(?s).*(?:老师|讲师).{0,24}(?:主讲|讲授|授课).*") &&
                !text.matches("(?s).*(?:本人|我)(?:独立|实际|亲自|负责|参与|曾|已经)?(?:主讲|讲授|授课).*")) return false;
        boolean ownParticipation=text.matches("(?s)^(?:本人|我)?参与(?:主讲|讲授|授课).*" );
        if(!ignoreParticipation && PARTICIPATION.matcher(text).find() && !ownParticipation && !ownTeaching(text)) return false;
        // An incidental attendance sheet is provenance, not an assistant role.
        if(!ownTeaching(text) && text.matches("(?s).*(?:助教|会务|报名|签到|会场安排|资料运营|课程库管理|课程结业).*")) return false;
        if(text.matches("(?s).*(?:邀请|特邀|协助|联合组织|安排|联络|承办|采购|外聘).{0,60}(?:主讲|授课|讲授).*") && !ownTeaching(text) && !personalCourseOffer(text)) return false;
        return true;
    }
    /** A distinct present capability assertion may coexist with participation
     * elsewhere on the same line. This never recovers the participation clause
     * or turns a certificate/ability statement into completed teaching. */
    static List<String> capabilitySentences(String line) {
        if(line.codePointCount(0,line.length())>220 || !PARTICIPATION.matcher(line).find() || allowed(line) || !allowed(line,true) ||
            !EvidenceSections.factContextSafe(line) ||
            Pattern.compile("假设|假如|如果|倘若|转述|引用|摘录|据说|示例|样例|范例|模板|原话|同事|他人|别人|他们|她|(?:^|[，,。；;])他|[“”\"]").matcher(line).find())return List.of();
        List<String> result=new ArrayList<>();
        java.util.regex.Matcher parts=Pattern.compile("[^。；;！？!?\\r\\n]+[。；;！？!?]?").matcher(line);
        while(parts.find()) {
            String sentence=parts.group().strip();
            if(sentence.equals(line.strip()) || sentence.codePointCount(0,sentence.length())>220 ||
                !sentence.matches("(?s)^(?:同时|此外|另外|并且)?(?:本人|我|该讲师|该教师|该老师)?(?:作为[^，,。；;！？!?]{2,40}讲师[，,])?(?:擅长|精通|熟悉).*") ||
                !allowed(sentence) || PARTICIPATION.matcher(sentence).find() || ownTeaching(sentence) ||
                !nonproofSpans(sentence).isEmpty())continue;
            result.add(sentence);
        }
        return result;
    }
    static List<String> units(String source) {
        String normalized=normalized(source);
        if(EvidenceSections.globalQualification(normalized)) return List.of();
        List<String> result=new ArrayList<>(); boolean excluded=false;int paragraphStart=0,sectionStart=0;
        // A comma promises a continuation even when PDF layout inserts a line
        // break. Inspect the contiguous group before quoting either half.
        for(PhysicalContinuation.Line physical:PhysicalContinuation.lines(normalized)) {
            String line=physical.text(normalized).strip();
            if(line.isBlank()) { paragraphStart=result.size();continue; }
            if(personalMetadataLine(line) || EvidenceSections.templateLine(line)) { paragraphStart=result.size();continue; }
            if(EXCLUDED_HEADING.matcher(line).matches() || line.matches("(?:团队|机构|公司)(?:案例|授课案例|授课经历|教学成果)[:：]?")) { excluded=true;paragraphStart=sectionStart=result.size();continue; }
            if(POSITIVE_HEADING.matcher(line).matches() || line.matches("(?:个人职责|个人案例|本人经历)[:：]?")) { excluded=false;paragraphStart=sectionStart=result.size();continue; }
            if(excluded) continue;
            if(negative(line) && line.matches("(?s)^(?:但|不过|然而|本人|我|本次|该课程|这门课|上述|以上|上行|前述|实际).*") && !result.isEmpty()) {
                // A pronoun/contrast denial can refer back across a line break.
                int from=line.matches("(?s).*(?:上述|以上|上行|前述|这些|全部|均未|都未).*")?sectionStart:paragraphStart;
                while(result.size()>from) result.remove(result.size()-1);
            }
            if(line.endsWith(","))continue; // Missing continuation is not complete evidence.
            if(sameTeacherContextSafe(line,normalized)) {
                if(allowed(line)) result.addAll(affirmativeParts(line));
                else if(roleSafeAt(normalized,normalized.indexOf(line),normalized.indexOf(line)+line.length(),0,normalized.length()))
                    result.addAll(capabilitySentences(line));
            }
        }
        return result;
    }
    static String positive(String text) { return String.join("\n",units(text)); }
    static List<Map<String,Object>> records(String source) {
        String normalized=normalized(source); List<Map<String,Object>> result=new ArrayList<>(); int cursor=0;
        List<PhysicalContinuation.Line> physical=PhysicalContinuation.lines(normalized);
        for(String unit:units(normalized)) {
            int offset=normalized.indexOf(unit,cursor); if(offset<0) continue; cursor=offset+unit.length();
            int line=1;for(int i=0;i<offset;i++) if(normalized.charAt(i)=='\n') line++;
            String role=isTeachingRecord(unit,normalized) && roleSafeAt(normalized,offset,offset+unit.length(),0,normalized.length())?"teaching_history_statement":
                    organizationClaim(unit)?"organization_statement":
                    unit.matches("(?s).*(?:主讲|讲授|授课|课程|擅长|精通|熟练).*" )?"course_statement":
                    unit.matches("(?s).*(?:服务案例|服务于|曾为|客户|分行|支行).*" )?"client_statement":"professional_statement";
            Map<String,Object> fullRecord=new LinkedHashMap<>(Map.of("evidence_id","line-"+line+"-"+offset,"text",unit,"source_line",line,
                    "source_offset",offset,"role",role,"verified",false));
            int contextStart=offset,contextEnd=offset+unit.length();
            while(contextStart>0 && "\r\n".indexOf(normalized.charAt(contextStart-1))<0) contextStart--;
            while(contextEnd<normalized.length() && "\r\n".indexOf(normalized.charAt(contextEnd))<0) contextEnd++;
            for(PhysicalContinuation.Line span:physical)if(span.start()<=offset&&offset+unit.length()<=span.end()) {
                contextStart=span.start();contextEnd=span.end();break;
            }
            String context=normalized.substring(contextStart,contextEnd);
            if(capabilitySentences(context).contains(unit)) {
                fullRecord.put("role","course_statement");
                fullRecord.put("scope_evidence_limit","content_only");
                fullRecord.put("context_source_offset",contextStart);fullRecord.put("context_text",context);
            }
            if(!nonproofSpans(context).isEmpty()||Pattern.compile("\\R").matcher(context).find()) {
                fullRecord.put("context_source_offset",contextStart);fullRecord.put("context_text",context);
            }
            result.add(fullRecord);
            // A qualification is not teaching history. Equally, a qualification
            // earlier on the same safe line must not erase a separate completed
            // event. Keep the event as an exact contiguous substring and retain
            // its full context; never transfer the certificate's topic to it.
            if(unit.matches("(?s).*(?:认证|证书|资格).*") && allowed(unit) && !organizationClaim(unit) && EvidenceSections.factContextSafe(unit)) {
                java.util.regex.Matcher clauses=Pattern.compile("[^，,。；;]+[。；;]?").matcher(unit);
                while(clauses.find()) {
                    String event=clauses.group().strip();
                    if(event.equals(unit) || event.matches("(?s).*(?:认证|证书|资格).*") || !isTeachingRecord(event,normalized)) continue;
                    int at=offset+clauses.start()+clauses.group().length()-clauses.group().stripLeading().length();
                    if(!roleSafeAt(normalized,at,at+event.length(),0,normalized.length())) continue;
                    Map<String,Object> record=new LinkedHashMap<>();
                    record.put("evidence_id","line-"+line+"-"+at);record.put("text",event);record.put("source_line",line);
                    record.put("source_offset",at);record.put("role","teaching_history_statement");record.put("verified",false);
                    record.put("context_source_offset",offset);record.put("context_text",unit);result.add(record);
                }
            }
        }
        return result;
    }
    static boolean isTeachingRecord(String unit,String source) {
        if(!allowed(unit) || organizationClaim(unit) || unit.matches("(?s).*(?:适配|希望|致力于|筹备|认证|证书).*")) return false;
        if(unit.matches("(?s).*(?:主讲课程[:：]|精品课程[:：]|计划|拟开设).*")) return false;
        if(unit.contains("带教") && !unit.matches("(?s).*(?:本人|我|该讲师)(?:独立|亲自|实际|已|已经|曾|曾经|参与|负责|于20\\d{2}年)?(?:主讲|讲授|授课).*")) return false;
        if(INDEPENDENT_DATED.matcher(unit).find()) return true;
        if(completedPersonalEvent(unit)) return true;
        if(ownTeaching(unit) && unit.matches("(?s).*(?:曾|已经|实际|累计|20\\d{2}|至今|多年|多次|讲完|完成|讲授过|主讲过).*")) return true;
        if(unit.matches("(?s)^(?:实际授课记录[:：]?)?(?:20\\d{2}年.{0,40}|曾|已经|实际|累计|多年|多次).*(?:主讲|讲授|授课|开展.{0,30}课堂).*") ||
                unit.matches("(?s)^(?:主讲过|讲授过|实际授课记录).*")) return true;
        if(unit.matches("(?s)^(?:本人|我|该讲师|该教师)(?:已|已经|曾|实际)(?:为|面向).{1,30}完成.{1,40}(?:工作坊|授课|课堂|教学).*") &&
            !unit.matches("(?s).*(?:协助|组织|安排|旁听|参训|邀请|团队).*") ) return true;
        int at=source.indexOf(unit),heading=at<0?-1:source.lastIndexOf("授课风采",at);
        return unit.length()>5 && at>=0 && heading>=0 && at-heading<2000 &&
                !source.substring(heading,at).matches("(?s).*(?:个人介绍|未来规划|计划课程|参训经历|认证经历|未授课课程|助教经历|服务客户|客户名单).*") &&
                !unit.matches("(?s).*(?:计划|拟|未来|讲师|老师|教师|资格|能力|擅长).*") &&
                unit.matches("(?s).*(?:培训|课堂|课程|授课).*");
    }
    static int grade(String text) {
        if(!allowed(text) || organizationClaim(text)) return 0;
        if(text.matches("(?s).*(?:主讲|教授|授课|教学|宣讲|情景演练|课堂|课程|擅长|精通|熟练|专业领域[:：]|专业方向[:：]|业务专长[:：]|✓).*")) return 2;
        if(text.length()<=32 && !text.matches("(?s).*(?:证书|认证|资格|学历|专业|从业|经历|经验|年|简介|介绍|精品课程|领域).*")) return 1;
        return 0;
    }
    private ProfessionalEvidence() {}
}
