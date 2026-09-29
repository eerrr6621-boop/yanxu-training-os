package com.training;

import java.util.*;
import java.util.regex.*;

/** Bounded narrative predicate/argument binding. It emits literal source fields,
 * never a generated biography or a verified credential. */
final class NarrativeTeachingEvents {
    private static final String SELF="(?:本人|我|该讲师|该教师|该老师)";
    private static final String MOD="(?:(?:已经|已|曾经|曾|实际|独立|亲自|从头到尾|全程|完整|于20[0-9]{2}年)){0,5}";
    private static final String REF=ProfessionalEvidence.COURSE_REFERENCE;
    private static final String CONTENT="[^，,。；;！？!?\\r\\n：:]{2,90}?";
    private static final Pattern ACTIVE=Pattern.compile("("+SELF+")("+MOD+")(?:(?:为|面向|给)([^，,。；;！？!?\\r\\n]{1,40}?))?(主讲|讲授|讲完)(过|了)?("+CONTENT+")$");
    private static final Pattern COMPLETED_OBJECT=Pattern.compile("("+SELF+")("+MOD+")完成(?:了|过)?("+CONTENT+")(?:的)?(?:全部)?(教学|讲授|授课)$");
    private static final Pattern FINISHED_COURSE=Pattern.compile("^(.{2,90}?)(课程|课|班)(?:已经|已|现已)?(结束|结课|讲完)(?:了)?$");
    private static final Pattern SUMMARY_COURSE=Pattern.compile("^(.{2,90}?)课(?:程)?的结课(?:小结|总结)$");
    private static final Pattern REFERENCED_FINISH=Pattern.compile("^(?:"+REF+"|课堂)(?:已经|已|现已)?(?:结束|结课|讲完)(?:了)?$");
    private static final Pattern ROLE=Pattern.compile("^(?:由)?("+SELF+")("+MOD+")(?:承担)?(主讲|讲授)(?:全课)?$");
    private static final Pattern PASSIVE=Pattern.compile("^(?:该课(?:程)?的|这门课(?:程)?的|本课(?:程)?的)?(?:教学|讲授|授课)由("+SELF+")("+MOD+")完成(?:了)?$");
    private static final Pattern AUDIENCE=Pattern.compile("^(?:本班|该班|这一班|本课程|该课程|这门课程)(?:的)?(?:全部|所有)?(?:学员|学生|对象)(?:均为|都是|全是|是|为|[:：])(.+)$");
    // These are declarations of an entity, not evidence that its author taught.
    private static final Pattern OPENING=Pattern.compile("^([^《》]{1,45}?)(?:(?:为|给|面向)([^《》]{1,45}?))?(?:开设|开办|举办)(?:了)?《([^《》]{2,90})》(?:课程|课|培训|训练)?$");
    private static final Pattern CONTENT_DECLARATION=Pattern.compile("^(?:学习内容|培训主题|训练内容|课程内容)(?:定为|确定为|为|是)《([^《》]{2,90})》$");
    private static final Pattern LEARNERS=Pattern.compile("^参加[^《》。；;]{0,18}(?:训练|培训|课程|学习)的(?:是|为)(.{2,50})$");
    private static final Pattern COURSE_PASSIVE=Pattern.compile("^("+REF+")由("+SELF+")("+MOD+")(讲完|完成(?:了)?(?:全课|全部课程)(?:的)?(?:教学|讲授|授课))(?:了)?$");
    private static final Pattern DENIAL=Pattern.compile("未(?:主讲|讲授|授课|讲完|完成)|不(?:是|代表|意味着)|并非|尚未|从未|仅(?:参训|旁听)|计划|拟(?:主讲|讲授|授课|开课|开设|完成|开展|由|于|在)|模板|示例|假设|虚构|更正|另有说明|实际由");
    private static final Pattern OTHER_TEACHING=Pattern.compile("(?:同事|他人|别人|其他(?:讲师|老师|教师)|另一位|外聘|受邀)[^，,。；;]{0,30}(?:主讲|讲授|授课)|(?:主讲|授课人|讲师)(?:是|为|[:：])(?!(?:本人|我|该讲师))");
    record Clause(String text,int start,int end) {}
    static boolean courseReference(String text) {return text.matches(REF+"(?:的)?(?:全部(?:内容)?|整门内容)?");}
    static void audience(TeachingEvents.Event e,String value,int start,int end) {
        TeachingEvents.Field prior=e.fields.get("audience");
        if(prior!=null&&value.matches("(?:这批|这些|本班|该班)(?:学员|学生|参训者|受训者)"))return;
        // A deictic profession is not a synonym lookup: retain the full earlier
        // group only when its literal nominal core occurs in that group.
        if(prior!=null&&value.matches("(?:这批|这些).{2,12}")) {
            String core=value.replaceFirst("^(?:这批|这些)","").replaceFirst("(?:生|人员|员工)$","");
            if(core.length()>=2&&prior.value().contains(core)&&!Pattern.compile("(?:非|不|未|无)"+Pattern.quote(core)).matcher(prior.value()).find())return;
        }
        field(e,"audience","叙述对象",value,start,end);
    }
    static boolean contextualActivity(String text) {
        // A bounded supporting event remains a relation, never lead/completion.
        if(DENIAL.matcher(text).find()||OTHER_TEACHING.matcher(text).find())return false;
        return text.matches("^(?:训练前|开班前|课前|培训前)?"+SELF+"(?:整理|准备)(?:了)?[^。；;]{1,50}(?:供课堂使用|用于课堂)$")||
            text.matches("^(?:最终材料中|归档材料中|课堂档案中)(?:既)?保存了[^。；;]{1,60}$")||
            text.matches("^(?:也)?保存了学员的[^。；;]{1,50}(?:练习|作业)$")||
            text.matches("^学员(?:将|把)[^。；;]{1,40}(?:交回|提交给|交给)[^。；;]{1,20}$")||
            text.matches("^从[^。；;]{1,60}(?:都是|均由)"+SELF+"(?:承担|讲授|讲解)$")||
            text.matches("^(?:实际)?(?:课堂讲解|讲解|答疑|收尾讨论)(?:(?:与|和|及)(?:课堂讲解|讲解|答疑|收尾讨论)){0,3}由"+SELF+"完成$");
    }
    static boolean classroomArtifact(String text) {
        return text.matches("^(?:课堂|课程|培训)(?:结束|结课)后(?:留下|保存)的是(?:教学步骤卡|课堂练习卡|练习卡|练习作业|练习记录|课堂笔记|学习笔记)$");
    }
    static boolean artifactLimitation(String text) {
        return text.matches("^而非[^，,。；;]{0,12}(?:业务|绩效|岗位)(?:考核|评估|考评)记录$")&&
            !text.matches(".*(?:本人|我|他人|同事|老师|讲师|教师|授课|主讲|讲授|不是|没有|未|计划).*" );
    }
    static boolean explicitLogistics(String text) {
        String task="(?:召集人员|通知学员|借用教室|布置场地|提供材料|准备教具|安排接送)";
        return text.matches("^(?:厂部|工厂|部门|学校|培训中心|单位|机构|公司|团队)(?:仅|只)负责"+task+"(?:(?:和|与|及|、)"+task+"){0,5}$");
    }
    static boolean organizationPreparation(String text) {
        // Logistical predicate + material objects. No teaching/actor/state is
        // inferred from it, and arbitrary residual narrative stays unparsed.
        String objects="(?:教室|场地|材料|样表|设备|教具|课件|纸张|桌椅)";
        return text.matches("^[^。；;，,]{0,20}(?:中心|机构|公司|单位|团队|工作室|学校)(?:准备|提供|布置)"+objects+"(?:(?:、|和|及)"+objects+"){0,5}(?:并通知学员)?$")&&
            !DENIAL.matcher(text).find()&&!OTHER_TEACHING.matcher(text).find()&&!text.matches(".*(?:本人|我|代替|他人|同事|假设|计划).*" );
    }
    static boolean timeOnly(String text) {return text.matches("(?:(?:20[0-9]{2}年)?(?:[一二三四五六七八九十]{1,3}|[0-9]{1,2})月|开班后|结课当天|培训结束后|训练前|课前)");}
    static boolean materialDescription(String text) {
        return text.matches("^(?:课程|本课|该课)(?:材料|讲义|课件)(?:说明|介绍)(?:了)?[\\p{IsHan}]{1,20}(?:与|和)[\\p{IsHan}]{1,20}的衔接$")&&
            !DENIAL.matcher(text).find()&&!OTHER_TEACHING.matcher(text).find()&&
            !text.matches(".*(?:本人|讲师|老师|教师|同事|他人|授课|主讲|讲授|资格|认证|证明|记录|计划|可能|不|未|假如|如果).*");
    }
    static void field(TeachingEvents.Event e,String key,String label,String value,int start,int end) {
        TeachingEvents.Field prior=e.fields.putIfAbsent(key,new TeachingEvents.Field(label,value,start,end));
        boolean sameSelf=key.equals("actor")&&prior!=null&&prior.value().matches(SELF)&&value.matches(SELF);
        if(prior!=null&&!prior.value().equals(value)&&!key.equals("completion")&&!sameSelf)e.issues.add("conflicting_narrative_field:"+key);
    }
    static void relation(TeachingEvents.Event e,String kind,Clause clause) {
        Map<String,Object> r=new LinkedHashMap<>(new TeachingEvents.Field(kind,clause.text,clause.start,clause.end).map(e.source));
        r.put("relation",kind);e.relations.add(r);
    }
    static boolean supportingClause(String text) {
        String materialUse="^"+SELF+"用[^，,。；;！？!?]{1,35}[，,](?:讲解|讲清|解释|示范|演示|说明)[^，,。；;！？!?]{1,60}[。.]?$";
        // A worked example used by the teacher is not a fabricated resume
        // example. The subject and the teaching action must both be explicit.
        if(text.matches(materialUse)&&!DENIAL.matcher(text.replace("示例", "例题")).find()&&!OTHER_TEACHING.matcher(text).find())return true;
        if(DENIAL.matcher(text).find()||OTHER_TEACHING.matcher(text).find()||text.matches("(?s).*"+SELF+"(?:仅|只)(?:负责|承担|整理|参与).*") )return false;
        boolean any=false;
        for(String raw:text.split("[，,。；;！？!?\\r\\n]+")) {
            String c=raw.strip();if(c.isEmpty())continue;
            // Supporting activity does not establish completion or a lead role.
            boolean activity=c.matches("^(?:(?:先|再|随后|接着|并|然后)?(?:由)?"+SELF+"|先|再|随后|接着|并|然后).*(?:讲解|讲清|讲评|解释|示范|演示|说明|教大家|带领|带他们|讲起|复盘答疑).*" )
                &&!c.matches(".*(?:邀请|安排|协助|教案|备课|课件制作|授课人|主讲人).*");
            activity|=c.matches("^(?:学员|学生)(?:提交|完成)[^，,。；;]{1,30}后(?:也)?是"+SELF+"(?:逐份|逐一|逐个)?讲评[^，,。；;]{0,40}$");
            boolean organization=organizationSupport(c);
            if(!activity&&!organization)return false;any=true;
        }
        return any;
    }
    static boolean organizationSupport(String clause) {
        return clause.matches("^(?:工作室|(?:本|所在)?(?:机构|公司|团队|单位)|其他项目成员)(?:仅|只)(?:提供|负责|承担)[^。；;]{0,60}(?:场地|材料|运输|设备|会务|接送)(?:和材料)?$")&&!DENIAL.matcher(clause).find()&&!OTHER_TEACHING.matcher(clause).find();
    }
    static String roleContextView(String source) {
        char[] view=source.toCharArray();Matcher clauses=Pattern.compile("[^，,。；;！？!?\\r\\n]+").matcher(source);
        while(clauses.find())if(organizationSupport(clauses.group().strip()))Arrays.fill(view,clauses.start(),clauses.end(),' ');
        return new String(view);
    }
    static void compositeRole(TeachingEvents.Event e) {
        TeachingEvents.Field actor=e.fields.get("actor");if(actor==null)return;
        String raw=actor.value();Matcher m=Pattern.compile("^("+SELF+")[，,](独立)(?:完成(?:了)?(?:全课|全部课程)(?:的)?讲授|主讲(?:全课)?)$").matcher(raw);
        if(!m.matches())return;
        if(!e.value("role").isBlank()&&!Set.of("主讲","独立主讲","讲授","授课教师").contains(e.value("role")))e.issues.add("conflicting_composite_role");
        if(raw.contains("完成")&&!e.value("completion").isBlank()&&!Set.of("已完成","已结课","已授课","已完成授课").contains(e.value("completion")))e.issues.add("conflicting_composite_completion");
        e.narrativeLead=true;e.narrativeIndependent=true;e.narrativeCompleted=raw.contains("完成");
        relation(e,"composite_personal_teaching",new Clause(raw,actor.start(),actor.end()));
    }
    static List<TeachingEvents.Event> extract(String source,List<TeachingEvents.Event> blocks) {
        List<TeachingEvents.Event> result=new ArrayList<>();
        Matcher paragraphs=Pattern.compile("(?s)(?:[^\\r\\n]|\\r?\\n(?![\\t ]*\\r?\\n))+").matcher(source);
        while(paragraphs.find()) {
            int start=paragraphs.start(),end=paragraphs.end();String paragraph=paragraphs.group();
            if(blocks.stream().anyMatch(b->b.start<end&&b.end>start))continue;
            if(!paragraph.matches("(?s).*(?:主讲|讲授|讲完|教学|授课由).*")||EvidenceSections.globalQualification(source))continue;
            List<Clause> clauses=new ArrayList<>();Matcher cm=Pattern.compile("[^，,。；;！？!?\\r\\n：:]+").matcher(paragraph);
            while(cm.find()){String c=cm.group().strip();if(!c.isEmpty()){int at=start+cm.start()+cm.group().indexOf(c);clauses.add(new Clause(c,at,at+c.length()));}}
            List<int[]> methodContext=ProfessionalEvidence.methodDescriptionSpans(paragraph);
            List<int[]> artifactContext=LearnerArtifactContext.spans(paragraph);
            List<InstructionalNarrativeContext.Span> instructionalContext=InstructionalNarrativeContext.spans(paragraph);
            List<InstructionalNarrativeContext.Span> acceptedInstructional=new ArrayList<>();
            TeachingEvents.Event current=null;List<Clause> prefix=new ArrayList<>();
            List<TeachingEvents.Event> found=new ArrayList<>();
            Set<String> titledCourses=new LinkedHashSet<>();Matcher titles=Pattern.compile("《([^《》]{2,90})》").matcher(paragraph);
            while(titles.find())titledCourses.add(titles.group(1));
            // Resolve only inside this full paragraph. A second title makes an
            // unqualified course pronoun ambiguous, even if it is less recent.
            if(titledCourses.size()==1)for(Clause c:clauses) {
                Matcher opening=OPENING.matcher(c.text),declared=CONTENT_DECLARATION.matcher(c.text);
                boolean opened=opening.matches(),declaration=declared.matches();
                if(!opened&&!declaration)continue;
                Matcher m=opened?opening:declared;int group=opened?3:1;
                current=new TeachingEvents.Event(source,start);current.form="narrative";current.end=end;
                field(current,"course","声明课程",m.group(group),c.start+m.start(group),c.start+m.end(group));
                break;
            }
            for(int clauseIndex=0;clauseIndex<clauses.size();clauseIndex++) {
                Clause c=clauses.get(clauseIndex);
                if(current!=null&&current.narrativeLead&&!current.value("actor").isBlank()&&titledCourses.size()==1) {
                    InstructionalNarrativeContext.Span context=instructionalContext.stream()
                        .filter(s->start+s.start()<=c.start&&c.end<=start+s.end()).findFirst().orElse(null);
                    if(context!=null) {
                        current.end=c.end;relation(current,context.kind(),c);
                        if(!acceptedInstructional.contains(context))acceptedInstructional.add(context);
                        continue;
                    }
                }
                if(current!=null&&artifactContext.stream().anyMatch(span->start+span[0]<=c.start&&c.end<=start+span[1])) {
                    current.end=c.end;relation(current,"learner_artifact_context_only",c);continue;
                }
                if(current!=null&&current.narrativeLead&&titledCourses.size()==1) {
                    if(c.text.matches("^课程(?:已经|已|现已)(?:结束|结课)(?:了)?$")) {
                        current.end=c.end;relation(current,"course_status_context_only",c);continue;
                    }
                    if(materialDescription(c.text)) {
                        current.end=c.end;relation(current,"material_description_context_only",c);continue;
                    }
                }
                NamedPassiveTeaching.Fact named=NamedPassiveTeaching.parse(c.text);
                if(named!=null) {
                    if(current!=null){current.end=c.start;found.add(current);}
                    current=new TeachingEvents.Event(source,c.start);current.form="narrative";current.end=c.end;
                    field(current,"course","被动句课程",named.course(),c.start+named.courseStart(),c.start+named.courseEnd());
                    field(current,"actor","被动句授课人",named.actor(),c.start+named.actorStart(),c.start+named.actorEnd());
                    if(!named.audience().isEmpty())audience(current,named.audience(),c.start+named.audienceStart(),c.start+named.audienceEnd());
                    if(!named.mode().isEmpty())field(current,"mode","被动句授课形式",named.mode(),c.start+named.modeStart(),c.start+named.modeEnd());
                    if(!named.date().isEmpty())field(current,"date","被动句时间",named.date(),c.start+named.dateStart(),c.start+named.dateEnd());
                    current.narrativeLead=true;current.narrativeCompleted=named.history();current.narrativeIndependent=named.independent();
                    current.completionScope=named.finished()?"full_course":named.history()?"past_teaching":"unspecified";
                    current.issues.addAll(named.issues());
                    NamedPassiveTeaching.Section section=NamedPassiveTeaching.excludedSection(source,c.start);
                    if(section!=null){current.issues.add("excluded_named_passive_section");current.unparsedContext.add(new TeachingEvents.Field("排除章节",section.text(),section.start(),section.end()).map(source));}
                    for(Clause p:prefix)if(!metadata(p.text)&&!temporalAdjunct(p.text)) {
                        current.issues.add("unparsed_narrative_preface");current.unparsedContext.add(new TeachingEvents.Field("未结构化前文",p.text,p.start,p.end).map(source));
                    }
                    prefix.clear();relation(current,"named_passive_personal_teaching",c);continue;
                }
                if(current!=null) {
                    Matcher opening=OPENING.matcher(c.text),declared=CONTENT_DECLARATION.matcher(c.text),learners=LEARNERS.matcher(c.text),cp=COURSE_PASSIVE.matcher(c.text);
                    if(opening.matches()&&opening.group(3).equals(current.value("course"))) {
                        if(opening.group(2)!=null)audience(current,opening.group(2),c.start+opening.start(2),c.start+opening.end(2));
                        relation(current,"course_offering_not_personal_teaching",c);continue;
                    }
                    if(declared.matches()&&declared.group(1).equals(current.value("course"))) {relation(current,"course_entity_declaration",c);continue;}
                    if(learners.matches()){audience(current,learners.group(1),c.start+learners.start(1),c.start+learners.end(1));relation(current,"same_event_audience",c);continue;}
                    InstructorMode.Continuation continuation=InstructorMode.continuation(c.text);
                    boolean learnerAntecedent=clauseIndex>0&&InstructorMode.learnerContext(clauses.get(clauseIndex-1).text)&&
                        c.text.matches("^(?:采用|使用).*");
                    if(continuation!=null&&!learnerAntecedent) {
                        current.end=c.end;
                        field(current,"mode","叙述形式",continuation.mode(),c.start+continuation.start(),c.start+continuation.end());
                        relation(current,"same_event_mode",c);continue;
                    }
                    if(cp.matches()) {
                        if(titledCourses.size()>1)current.issues.add("ambiguous_course_reference");
                        field(current,"actor","叙述主体",cp.group(2),c.start+cp.start(2),c.start+cp.end(2));
                        current.narrativeLead=true;current.narrativeCompleted=true;current.narrativeIndependent|=cp.group(3).contains("独立");
                        if(!current.completionScope.isEmpty())current.completionScope="full_course";
                        Matcher mode=Pattern.compile("面授|线上|远程|线下").matcher(cp.group(1));
                        if(mode.find())field(current,"mode","叙述形式",mode.group(),c.start+cp.start(1)+mode.start(),c.start+cp.start(1)+mode.end());
                        relation(current,"referenced_passive_personal_completion",c);continue;
                    }
                    if(c.text.matches("^(?:班级|本班|该班)(?:已经|已|现已)?结业(?:了)?$")){relation(current,"learner_cohort_completed_not_personal_role",c);continue;}
                    if(timeOnly(c.text)){relation(current,"temporal_context_only",c);continue;}
                    if(ProfessionalEvidence.organizationWorkExclusion(c.text)&&ProfessionalEvidence.referencedPersonalCompletion(paragraph)) {relation(current,"organization_work_exclusion_not_personal_denial",c);continue;}
                    boolean artifactTail=clauseIndex>0&&classroomArtifact(clauses.get(clauseIndex-1).text)&&artifactLimitation(c.text)&&
                        source.substring(clauses.get(clauseIndex-1).end,c.start).matches("[，,\\t ]+");
                    if(contextualActivity(c.text)||organizationPreparation(c.text)||explicitLogistics(c.text)||classroomArtifact(c.text)||artifactTail){
                        current.end=c.end;relation(current,"supporting_context_only",c);continue;
                    }
                }
                if(current!=null&&REFERENCED_FINISH.matcher(c.text).matches()) {current.end=c.end;current.narrativeCompleted=true;if(!current.completionScope.isEmpty())current.completionScope="full_course";relation(current,"referenced_course_completed",c);continue;}
                Matcher a=ACTIVE.matcher(c.text),done=COMPLETED_OBJECT.matcher(c.text),finished=FINISHED_COURSE.matcher(c.text),summary=SUMMARY_COURSE.matcher(c.text);
                boolean active=a.find(),completed=done.find(),finish=finished.matches(),summ=summary.matches();
                // A found predicate may have a temporal adjunct, but an
                // arbitrary omitted prefix can redefine who is speaking.
                boolean prefixUnsafe=(active&&a.start()>0&&!temporalAdjunct(c.text.substring(0,a.start())))||
                    (completed&&done.start()>0&&!temporalAdjunct(c.text.substring(0,done.start())));
                String course="";int courseStart=-1,courseEnd=-1;
                if(active){course=a.group(6);courseStart=c.start+a.start(6);courseEnd=c.start+a.end(6);}
                else if(completed){course=done.group(3).replaceFirst("(?:的)?全部$","").replaceFirst("的$","");courseStart=c.start+done.start(3);courseEnd=courseStart+course.length();}
                else if(finish){course=finished.group(1);courseStart=c.start+finished.start(1);courseEnd=c.start+finished.end(1);}
                else if(summ){course=summary.group(1);courseStart=c.start+summary.start(1);courseEnd=c.start+summary.end(1);}
                boolean ref=courseReference(course);
                if(ref&&current!=null&&titledCourses.size()>1)current.issues.add("ambiguous_course_reference");
                if(!course.isEmpty()&&!ref) {
                    String clean=course.replaceFirst("^《","").replaceFirst("》(?:课程|课)?$","");
                    if(!clean.equals(course)){courseStart+=course.startsWith("《")?1:0;course=clean;courseEnd=courseStart+clean.length();}
                    if(current==null||!current.value("course").equals(course)) {
                        if(current!=null){current.end=c.start;found.add(current);}
                        current=new TeachingEvents.Event(source,c.start);current.form="narrative";current.end=c.end;
                        field(current,"course","叙述课程",course,courseStart,courseEnd);
                        for(Clause p:prefix)if(!metadata(p.text)&&!temporalAdjunct(p.text)) {
                            current.issues.add("unparsed_narrative_preface");
                            current.unparsedContext.add(new TeachingEvents.Field("未结构化前文",p.text,p.start,p.end).map(source));
                        }
                        prefix.clear();
                    }
                }
                if(current==null){prefix.add(c);continue;}
                current.end=c.end;
                if(prefixUnsafe)current.issues.add("unparsed_actor_preface");
                if(active||completed) {
                    Matcher m=active?a:done;String actor=m.group(1);
                    if(active&&ProfessionalEvidence.arrangedTeaching(c.text))current.issues.add("arranged_action_not_personal_teaching");
                    field(current,"actor","叙述主体",actor,c.start+m.start(1),c.start+m.end(1));
                    current.narrativeLead=true;current.narrativeIndependent|=m.group(2).contains("独立");
                    current.narrativeCompleted|=completed||active&&(a.group(2).matches(".*(?:已|曾).*")||a.group(5)!=null||a.group(4).equals("讲完"));
                    boolean fullModifier=m.group(2).contains("完整");
                    if(active&&a.group(3)!=null) {
                        String recipient=a.group(3),modifier="";Matcher tail=Pattern.compile("(?:独立|亲自|全程|从头到尾|完整)+$").matcher(recipient);
                        if(tail.find()){
                            String core=recipient.substring(0,tail.start());
                            // A qualified/negated action modifier is not part
                            // of an audience, but cannot be silently made true.
                            if(core.isBlank()||core.matches(".*(?:不|未|尚未|并非|非|不够|未能|未必|没有|基本|大致|可能|尽量|尝试|部分|计划|将|拟|是否|要求|需要|安排)$"))
                                current.issues.add("ambiguous_recipient_action_modifier");
                            else {modifier=tail.group();recipient=core;}
                        }
                        current.narrativeIndependent|=modifier.contains("独立");
                        fullModifier|=modifier.contains("完整");
                        if(recipient.isBlank())current.issues.add("empty_narrative_audience");
                        else audience(current,recipient,c.start+a.start(3),c.start+a.start(3)+recipient.length());
                    }
                    // Complete wording alone is not past tense. Conversely,
                    // a past lecture is not automatically a complete course.
                    if(completed||active&&a.group(4).equals("讲完")||current.narrativeCompleted&&fullModifier)
                        current.completionScope="full_course";
                    else if(current.narrativeCompleted&&!current.completionScope.equals("full_course"))current.completionScope="past_teaching";
                    else if(current.completionScope.isEmpty())current.completionScope="unspecified";
                    relation(current,completed?"personal_completion_of_teaching":"personal_teaching",c);continue;
                }
                if(finish||summ){current.narrativeCompleted=true;if(!current.completionScope.isEmpty())current.completionScope="full_course";relation(current,"course_completed",c);continue;}
                InstructorMode.SourceDeclaration mode=InstructorMode.sourceDeclaration(c.text);
                if(mode!=null&&!current.value("course").isBlank()&&!current.value("actor").isBlank()) {
                    field(current,"actor","授课形式声明人",mode.actor(),c.start+mode.actorStart(),c.start+mode.actorEnd());
                    field(current,"mode","本人授课形式",mode.mode(),c.start+mode.modeStart(),c.start+mode.modeEnd());
                    relation(current,"personal_mode_of_same_course",c);continue;
                }
                if(!current.value("actor").isBlank()&&(InstructorMode.learnerContext(c.text)||
                        methodContext.stream().anyMatch(span->start+span[0]<=c.start&&c.end<=start+span[1]))) {
                    relation(current,"learner_or_method_context_only",c);continue;
                }
                Matcher role=ROLE.matcher(c.text),passive=PASSIVE.matcher(c.text),audience=AUDIENCE.matcher(c.text);
                if(role.matches()||passive.matches()) {
                    Matcher m=role.matches()?role:passive;
                    field(current,"actor","叙述主体",m.group(1),c.start+m.start(1),c.start+m.end(1));
                    current.narrativeLead=true;current.narrativeIndependent|=m.group(2).contains("独立");current.narrativeCompleted|=m==passive;
                    if(m==passive&&!current.completionScope.isEmpty())current.completionScope="full_course";
                    relation(current,m==passive?"passive_personal_teaching_completed":"personal_lead_of_course",c);continue;
                }
                if(audience.matches()) {field(current,"audience","同班学员对象",audience.group(1),c.start+audience.start(1),c.start+audience.end(1));relation(current,"same_event_audience",c);continue;}
                if(c.text.matches("^"+REF+"(?:已经|已|现已)?(?:结束|结课|讲完)(?:了)?$")){current.narrativeCompleted=true;relation(current,"referenced_course_completed",c);continue;}
                if(c.text.matches("^(?:这里所说的)?"+REF+"(?:就是|是)"+Pattern.quote(current.value("course"))+"$")){relation(current,"explicit_course_reference",c);continue;}
                if(metadata(c.text)||supportingClause(c.text)){relation(current,"supporting_context_only",c);continue;}
                current.issues.add("unparsed_narrative_clause");current.unparsedContext.add(new TeachingEvents.Field("未结构化叙述",c.text,c.start,c.end).map(source));
            }
            if(current!=null)found.add(current);
            for(TeachingEvents.Event event:found) {
                if(!event.narrativeLead&&event.relations.stream().anyMatch(r->Set.of("course_offering_not_personal_teaching","course_entity_declaration").contains(r.get("relation"))))
                    event.issues.add("course_entity_without_personal_teaching_relation");
                // A contradiction in the paragraph cannot be erased by taking a
                // small affirmative span. Separate paragraphs still have the
                // existing section/global conflict checks in the caller.
                char[] qualificationView=paragraph.toCharArray();
                for(int[] span:ProfessionalEvidence.methodContrastSpans(paragraph))Arrays.fill(qualificationView,span[0],span[1],' ');
                for(int[] span:artifactContext)Arrays.fill(qualificationView,span[0],span[1],' ');
                // Only the literal material noun inside an accepted, explicit
                // self-owned teaching-support clause is not a fake-CV marker.
                // All other occurrences and all other qualifications remain.
                for(InstructionalNarrativeContext.Span span:acceptedInstructional)
                    if(span.kind().equals("owned_example_explanation_context")) {
                        Matcher examples=Pattern.compile("示例").matcher(paragraph.substring(span.start(),span.end()));
                        while(examples.find())Arrays.fill(qualificationView,span.start()+examples.start(),span.start()+examples.end(),' ');
                    }
                if(DENIAL.matcher(new String(qualificationView)).find()||ProfessionalEvidence.negative(paragraph)||OTHER_TEACHING.matcher(paragraph).find()||ProfessionalEvidence.otherActor(paragraph))event.issues.add("narrative_role_or_state_qualification");
                if(ProfessionalEvidence.ROLE_CONTEXT.matcher(roleContextView(paragraph)).find())event.issues.add("narrative_assistant_or_attendee_role");
                if(!ProfessionalEvidence.roleSafeAt(roleContextView(source),event.start,event.end,0,source.length()))event.issues.add("document_role_context_conflict");
                if(source.matches("(?s).*(?:本人|第一人称)[^。；;\\r\\n]{0,30}(?:指同事|指他人|代表同事|代表他人)|.*(?:同事|他人)[^。；;\\r\\n]{0,30}(?:第一人称|代写|填写).*") )event.issues.add("document_actor_reference_ambiguous");
                result.add(event);
            }
        }
        return result;
    }
    static boolean metadata(String text){return text.matches("[^，,。；;]{0,50}(?:活动回顾|项目总结|课程总结|结课小结|教学记录|授课记录)")&&!DENIAL.matcher(text).find()&&!text.matches(".*(?:引用|转述|摘录|范文|代写).*" )&&!text.matches("^(?:而非|非本人|非个人).*" );}
    static boolean temporalAdjunct(String text){return text.matches("(?:上次|此前|去年|今年|20[0-9]{2}年)(?:的)?[^，,。；;]{0,35}(?:课|课程|班|工作坊|活动)(?:里|中|上)")&&!DENIAL.matcher(text).find()&&!OTHER_TEACHING.matcher(text).find();}
    private NarrativeTeachingEvents(){}
}
