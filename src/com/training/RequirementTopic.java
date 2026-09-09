package com.training;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** Bounded requirement syntax only. A course/history node is not proof about a
 * teacher, and consumed text is not an eligibility or qualification decision.
 * No domain vocabulary, model score, source evidence or network is consulted. */
final class RequirementTopic {
    static final String VERSION="requirement_topic_v1";
    private static final Pattern FIELD=Pattern.compile("^\\s*(补充要求|客户单位|培训主题|培训内容|课程主题|课程内容|课程|主题|参训对象|培训对象|授课对象|学员对象|受众|希望解决的问题|培训目标|期望日期|授课日期|预计课时|师资要求|补充说明)\\s*[:：]");
    private static final Set<String> TOPIC_FIELDS=Set.of("培训主题","培训内容","课程主题","课程内容","课程","主题");
    private static final Set<String> FREE_FIELDS=Set.of("师资要求","补充说明","补充要求");
    private static final Set<String> AUDIENCE_FIELDS=Set.of("参训对象","培训对象","授课对象","学员对象","受众");
    private static final Pattern TITLE=Pattern.compile("《([^《》\\r\\n]{2,80})》");
    private static final String SINGULAR="(?:这门课程|这一课程|这个课程|该课程|这门课|该课|这一主题|这个主题|该主题)";
    private static final String PLURAL="(?:上述|以上|这)(?:[一二两三四五六七八九十0-9]+门)?(?:课程|课)|(?:上述|以上|这些)(?:课程|主题)|各门课程";
    private static final Pattern REFERENCE=Pattern.compile("(?:"+SINGULAR+"|"+PLURAL+")");
    private static final String MARK="@COURSE@";
    // Closed syntactic roles: unknown actors/conditions cannot be absorbed by a
    // wildcard surrounding a recognised teaching word.
    private static final String PREFIX="(?:(?:请|希望|需要|要求|必须|须|需|应|还|且|并|提供|找)){0,6}";
    private static final String MOD="(?:(?:由|本人|老师|讲师|教师|已(?:经)?|实际|曾(?:经)?|亲自|独立)){0,10}";
    private static final String SUFFIX="(?:的)?(?:已(?:经)?完成)?(?:实际)?(?:授课|教学|课堂|课程)?(?:经历|记录)";
    private static final String PAST_ACTION="(?:教过|教了|讲过|讲了|讲授过|讲授了|主讲过|主讲了|教授过|教授了)";
    private static final String COMPLETE_ACTION="(?:讲完|完成过|完成了|完成)";
    private static final Pattern HISTORY=Pattern.compile("^"+PREFIX+MOD+"(?:"+
            PAST_ACTION+MARK+"(?:课程|教学|授课)?(?:"+SUFFIX+")?|"+
            COMPLETE_ACTION+MARK+"(?:的)?(?:课程|教学|授课)?(?:(?:并|且)?(?:有|提供))?"+SUFFIX+"|"+
            "(?:有|具有|具备|提供)(?:"+MARK+"(?:的)?)?(?:实际)?(?:授课|教学|课堂)?(?:经历|记录)|"+
            MARK+"(?:均|都|分别)?"+PREFIX+"(?:有|具有|具备|提供)(?:实际)?(?:授课|教学|课堂)?(?:经历|记录))$");
    private static final Pattern COMPLETED_PAST=Pattern.compile("^"+PREFIX+MOD+COMPLETE_ACTION+MARK+"(?:的)?(?:教学|授课|课程)$");
    private static final Pattern PLAIN_ACTION=Pattern.compile("^("+PREFIX+MOD+PAST_ACTION+")([^《》]{2,80}?)(?:课程)?$");
    private static final String REQUEST_LEAD="(?:(?:已经有|已有|具有|具备|提供|本人|老师|讲师|教师|已经|曾经|亲自|独立|实际|希望|需要|要求|必须|请|须|需|应|还|且|并|已|曾|有|找|由)){0,14}";
    private static final String ACTION="(?:主讲|讲授|讲完|完成|教授|教)(?:过|了)?";
    private static final Pattern LINKED_ACTION=Pattern.compile("^"+REQUEST_LEAD+ACTION+MARK+"(?:的)?(?:教学|授课|课程)?(?:(?:并|且)?(?:有|提供))?(?:"+SUFFIX+")?$");
    private static final Pattern LINKED_RECORD=Pattern.compile("^"+REQUEST_LEAD+"(?:"+MARK+")?"+SUFFIX+"$|^"+REQUEST_LEAD+MARK+"(?:均|都|分别)"+REQUEST_LEAD+SUFFIX+"$");
    private static final Pattern AUDIENCE_REFERENCE=Pattern.compile("(?:面向|为|给)(该对象|上述对象|这一对象)");

    /** Java UTF-16 offsets internally, explicit code-point offsets on the wire. */
    record Span(int start,int end,String text) {
        Map<String,Object> toMap(String source,String sourceKind) {
            if(start<0 || end<start || end>source.length() || !source.substring(start,end).equals(text))
                throw new IllegalArgumentException("Requirement source span mismatch");
            return Map.of("text",text,"source_start",source.codePointCount(0,start),"source_end",source.codePointCount(0,end),
                    "offset_unit","unicode_code_point","source",sourceKind);
        }
    }
    record HistoryCondition(boolean pastTeaching,boolean personal,boolean completed,boolean independent,Span span,String binding,List<Span> audienceBindings,List<Span> audienceReferences,List<Span> modeBindings) {
        HistoryCondition {audienceBindings=List.copyOf(audienceBindings);audienceReferences=List.copyOf(audienceReferences);modeBindings=List.copyOf(modeBindings);}
        HistoryCondition(boolean pastTeaching,boolean personal,boolean completed,boolean independent,Span span,String binding,List<Span> audienceBindings,List<Span> audienceReferences) {
            this(pastTeaching,personal,completed,independent,span,binding,audienceBindings,audienceReferences,List.of());
        }
        Map<String,Object> toMap(String source,String sourceKind) {
            Map<String,Object> result=new LinkedHashMap<>(Map.of("past_teaching",pastTeaching,"personal",personal,"completed",completed,"independent",independent,
                    "source_span",span.toMap(source,sourceKind),"binding",binding,
                    "audience_bindings",audienceBindings.stream().map(s->s.toMap(source,sourceKind)).toList(),
                    "audience_references",audienceReferences.stream().map(s->s.toMap(source,sourceKind)).toList()));
            if(!modeBindings.isEmpty())result.put("mode_bindings",modeBindings.stream().map(s->s.toMap(source,sourceKind)).toList());
            return result;
        }
    }
    record Course(String id,String canonicalAnchor,List<Span> anchorSpans,List<Span> referenceSpans,List<HistoryCondition> historyConditions) {
        Course {anchorSpans=List.copyOf(anchorSpans);referenceSpans=List.copyOf(referenceSpans);historyConditions=List.copyOf(historyConditions);}
        boolean requiresHistory() {return historyConditions.stream().anyMatch(HistoryCondition::pastTeaching);}
        Map<String,Object> toMap(String source,String kind) {
            return Map.of("course_id",id,"canonical_anchor",canonicalAnchor,"anchor_spans",anchorSpans.stream().map(s->s.toMap(source,kind)).toList(),
                    "reference_spans",referenceSpans.stream().map(s->s.toMap(source,kind)).toList(),"requires_history",requiresHistory(),
                    "history_conditions",historyConditions.stream().map(h->h.toMap(source,kind)).toList());
        }
    }
    record Consumption(String kind,Span span,List<String> courseIds) { Consumption {courseIds=List.copyOf(courseIds);} }
    record Unparsed(String reason,Span span) {}
    record Result(String source,String sourceKind,List<Course> courses,List<Consumption> consumedSpans,List<Unparsed> unparsedClauses,Map<String,Object> inputProvenance) {
        Result {courses=List.copyOf(courses);consumedSpans=List.copyOf(consumedSpans);unparsedClauses=List.copyOf(unparsedClauses);inputProvenance=Collections.unmodifiableMap(new LinkedHashMap<>(inputProvenance));}
        Map<String,Object> toMap() {
            return Map.of("schema_version",VERSION,"source",source,"source_kind",sourceKind,
                    "courses",courses.stream().map(c->c.toMap(source,sourceKind)).toList(),
                    "consumed_spans",consumedSpans.stream().map(c->Map.of("kind",c.kind(),"source_span",c.span().toMap(source,sourceKind),"course_ids",c.courseIds())).toList(),
                    "unparsed_clauses",unparsedClauses.stream().map(u->Map.of("reason",u.reason(),"source_span",u.span().toMap(source,sourceKind))).toList(),
                    "input_provenance",inputProvenance,"eligibility_decision",false,
                    "notice","仅承载课程与有界经历要求；未承载条件必须交后续检查，不能以本结果自动准入。");
        }
    }
    private static final class MutableCourse {
        final String id,canonical;final List<Span> anchors=new ArrayList<>(),references=new ArrayList<>();final List<HistoryCondition> history=new ArrayList<>();
        MutableCourse(String id,String canonical){this.id=id;this.canonical=canonical;}
        Course freeze(){return new Course(id,canonical,anchors,references,history);}
    }
    private record Target(Span anchor,int replaceStart,int replaceEnd) {}
    private final String source,sourceKind;
    private final Map<String,Object> provenance;
    private final LinkedHashMap<String,MutableCourse> nodes=new LinkedHashMap<>();
    private final List<Consumption> consumed=new ArrayList<>();
    private final List<Unparsed> unparsed=new ArrayList<>();
    private List<MutableCourse> focus=List.of();
    private final List<Span> audiences=new ArrayList<>();
    private RequirementTopic(String source,String sourceKind,Map<String,Object> provenance) {
        if(source==null || source.length()>10000)throw new IllegalArgumentException("Requirement text must be non-null and at most 10000 Java characters");
        this.source=source;this.sourceKind=sourceKind;this.provenance=provenance;
    }
    static Result parse(String source){return new RequirementTopic(source,"requirement_original",Map.of()).parse();}
    static boolean containsReference(String text){return REFERENCE.matcher(text).find();}
    static Result parse(RequirementInput input) {
        Objects.requireNonNull(input);
        return new RequirementTopic(input.canonicalText,input.guided?"requirement_canonical":"legacy_composed_requirement",input.toMap()).parse();
    }
    private Span span(int start,int end){return new Span(start,end,source.substring(start,end));}
    private Span trimmed(int start,int end) {
        while(start<end&&Character.isWhitespace(source.charAt(start)))start++;
        while(end>start&&Character.isWhitespace(source.charAt(end-1)))end--;
        return span(start,end);
    }
    private Result parse() {
        List<PostposedExclusion.Span> exclusions=PostposedExclusion.spans(source);
        for(PostposedExclusion.Span excluded:exclusions) {
            Span original=span(excluded.start(),excluded.end());
            consumed.add(new Consumption("excluded_requirement_context",original,List.of()));
            unparsed.add(new Unparsed("postposed_exclusion_requires_review",original));
        }
        Matcher lines=Pattern.compile("[^\\r\\n]+").matcher(source);int previousEnd=0;
        while(lines.find()) {
            if(source.substring(previousEnd,lines.start()).matches("(?s).*\\R\\s*\\R.*"))focus=List.of();
            previousEnd=lines.end();String line=lines.group();Matcher field=FIELD.matcher(line);int start=lines.start();String label="";
            if(field.find()) {
                label=field.group(1);consumed.add(new Consumption("field_boundary",span(start,start+field.end()),List.of()));start+=field.end();
                if(!TOPIC_FIELDS.contains(label)&&!FREE_FIELDS.contains(label)) {
                    Span rest=trimmed(start,lines.end());if(!rest.text().isEmpty()){unparsed.add(new Unparsed("delegated_field:"+label,rest));if(AUDIENCE_FIELDS.contains(label))audiences.add(rest);}continue;
                }
            }
            boolean first=true;
            for(Span originalClause:clauses(start,lines.end())) {
                if(PostposedExclusion.overlaps(exclusions,originalClause.start(),originalClause.end())){first=false;continue;}
                Span clause=originalClause;String clauseLabel=first?label:"";
                Matcher inlineField=FIELD.matcher(clause.text());
                if(inlineField.find()) {
                    clauseLabel=inlineField.group(1);
                    consumed.add(new Consumption("field_boundary",span(clause.start(),clause.start()+inlineField.end()),List.of()));
                    clause=trimmed(clause.start()+inlineField.end(),clause.end());
                }
                if(clause.text().isEmpty())continue;
                if(!clauseLabel.isEmpty()&&!TOPIC_FIELDS.contains(clauseLabel)&&!FREE_FIELDS.contains(clauseLabel)) {
                    unparsed.add(new Unparsed("delegated_field:"+clauseLabel,clause));
                    if(AUDIENCE_FIELDS.contains(clauseLabel))audiences.add(clause);
                } else if(TOPIC_FIELDS.contains(clauseLabel)) {
                    List<Target> titles=topicTargets(clause);
                    if(!titles.isEmpty())acceptTopics(clause,titles);
                    else {focus=List.of();unparsed.add(new Unparsed("unresolved_topic_declaration",clause));}
                } else if(!explicitHistoricalRequest(clause)&&!narrativeDeclaration(clause)&&!narrativeHistory(clause)&&!history(clause)) {
                    List<Target> titles=topicTargets(clause);
                    boolean standalone=clauseLabel.isEmpty() && label.isEmpty() && clause.text().startsWith("《") && titles.size()==1;
                    if(standalone)acceptTopics(clause,titles);
                    else unparsed.add(new Unparsed("unparsed_condition_or_context",clause));
                }
                first=false;
            }
        }
        return new Result(source,sourceKind,nodes.values().stream().map(MutableCourse::freeze).toList(),consumed,unparsed,provenance);
    }
    /** Split only true top-level delimiters; title/conjunction text stays intact. */
    private List<Span> clauses(int start,int end) {
        List<Span> result=new ArrayList<>();int part=start,paren=0;Deque<Character> quotes=new ArrayDeque<>();
        for(int i=start;i<end;i++) {
            char c=source.charAt(i);
            if(!quotes.isEmpty()&&c==quotes.peek()){quotes.pop();continue;}
            char closing=HistoricalTeachingRequest.closing(c);if(closing!=0){quotes.push(closing);continue;}
            if(!quotes.isEmpty())continue;
            if(c=='('||c=='（')paren++;if(c==')'||c=='）')paren--;
            if(paren==0&&"，,。；;！？!?".indexOf(c)>=0){result.add(trimmed(part,i));part=i+1;}
        }
        result.add(trimmed(part,end));return result;
    }
    private static boolean simpleAnchor(String text) {
        return text.length()>=2&&text.length()<=80&&!REFERENCE.matcher(text).find() &&
                !text.matches("(?s).*(?:必须|要求|需要|希望|不得|不能|不需要|无需|不教|未教|不讲|未讲|计划|下月|明年|已教|讲过|教过|完成|经历|记录|本人|讲师|老师|至少|每场|每次|或|[：:《》<>≥≤]).*");
    }
    private List<Target> topicTargets(Span clause) {
        String raw=clause.text();List<Target> titles=new ArrayList<>();Matcher m=TITLE.matcher(raw);int last=0;
        while(m.find()) {
            String gap=raw.substring(last,m.start()).strip();
            if(!gap.isEmpty()&&!gap.matches("以及|并且|和|与|及|、"))return List.of();
            titles.add(new Target(span(clause.start()+m.start(1),clause.start()+m.end(1)),clause.start()+m.start(),clause.start()+m.end()));last=m.end();
        }
        if(!titles.isEmpty())return raw.substring(last).strip().matches("(?:课程)?")?titles:List.of();
        List<Target> result=new ArrayList<>();Matcher separators=Pattern.compile("以及|并且|和|与|及|、").matcher(raw);int begin=0;
        while(separators.find()) {if(!addPlain(result,clause,begin,separators.start()))return List.of();begin=separators.end();}
        if(!addPlain(result,clause,begin,raw.length()))return List.of();return result;
    }
    private boolean addPlain(List<Target> values,Span clause,int begin,int end) {
        Span target=trimmed(clause.start()+begin,clause.start()+end);String text=target.text();
        if(text.endsWith("课程")&&text.length()>4)target=trimmed(target.start(),target.end()-2);
        if(!simpleAnchor(target.text()))return false;
        values.add(new Target(target,target.start(),target.end()));return true;
    }
    private MutableCourse node(Span anchor) {
        String canonical=Normalizer.normalize(anchor.text().strip(),Normalizer.Form.NFKC);
        MutableCourse node=nodes.computeIfAbsent(canonical,k->new MutableCourse("course_"+(nodes.size()+1),k));
        if(!node.anchors.contains(anchor))node.anchors.add(anchor);return node;
    }
    private void acceptTopics(Span clause,List<Target> targets) {
        focus=targets.stream().map(t->node(t.anchor())).distinct().toList();
        consumed.add(new Consumption("course_declaration",clause,focus.stream().map(n->n.id).toList()));
    }
    private boolean narrativeDeclaration(Span clause) {
        RequirementNarrative.Declaration declaration=RequirementNarrative.declaration(clause.text());
        if(declaration==null)return false;
        RequirementNarrative.Range target=declaration.course(),audience=declaration.audience();
        Span receiver=audience==null?null:span(clause.start()+audience.start(),clause.start()+audience.end());
        if(receiver!=null)audiences.add(receiver);
        if(declaration.stageOnly()) {
            // A course's stage is not its professional subject. Preserve both
            // the stage and audience, without inventing a course antecedent.
            focus=List.of();
            consumed.add(new Consumption("training_program_context",clause,List.of()));
            consumed.add(new Consumption("training_stage",span(clause.start()+target.start(),clause.start()+target.end()),List.of()));
            if(receiver!=null)consumed.add(new Consumption("narrative_audience",receiver,List.of()));
            return true;
        }
        MutableCourse course=node(span(clause.start()+target.start(),clause.start()+target.end()));focus=List.of(course);
        consumed.add(new Consumption("course_declaration",clause,List.of(course.id)));
        if(receiver!=null)consumed.add(new Consumption("narrative_audience",receiver,List.of(course.id)));
        if(declaration.mode()!=null) {
            RequirementNarrative.Range mode=declaration.mode();
            consumed.add(new Consumption("narrative_mode",span(clause.start()+mode.start(),clause.start()+mode.end()),List.of(course.id)));
        }
        return true;
    }
    private boolean explicitHistoricalRequest(Span clause) {
        HistoricalTeachingRequest.Fact fact=HistoricalTeachingRequest.parse(clause.text());if(fact==null)return false;
        // Preserve established named-course predicates; this path adds inline
        // recipient/mode bindings and quoted method objects, not a reinterpretation
        // of every previously supported plain 《course》 history declaration.
        if(fact.audience()==null&&fact.mode()==null&&clause.text().contains("《"))return false;
        HistoricalTeachingRequest.Part anchor=fact.topic();
        MutableCourse course=node(span(clause.start()+anchor.start(),clause.start()+anchor.end()));focus=List.of(course);
        HistoricalTeachingRequest.Part audience=fact.audience(),mode=fact.mode();
        course.history.add(new HistoryCondition(true,true,fact.completed(),fact.independent(),clause,"explicit_personal_history",
            audience==null?List.of():List.of(span(clause.start()+audience.start(),clause.start()+audience.end())),List.of(),
            mode==null?List.of():List.of(span(clause.start()+mode.start(),clause.start()+mode.end()))));
        consumed.add(new Consumption("teaching_history_condition",clause,List.of(course.id)));return true;
    }
    private boolean narrativeHistory(Span clause) {
        if(focus.size()!=1)return false;
        List<Span> receivers=audiences.stream().distinct().toList();
        RequirementNarrative.History relation=RequirementNarrative.history(clause.text(),receivers.size()==1?receivers.get(0).text():null);
        if(relation==null)return false;
        List<Span> refs=relation.references().stream().map(r->span(clause.start()+r.start(),clause.start()+r.end())).toList();
        List<Span> audienceRefs=relation.audienceReferences().stream().map(r->span(clause.start()+r.start(),clause.start()+r.end())).toList();
        MutableCourse course=focus.get(0);course.references.addAll(refs);
        course.history.add(new HistoryCondition(true,true,true,relation.independent(),clause,"narrative_single_course",
            audienceRefs.isEmpty()?List.of():receivers,audienceRefs,relation.mode()==null?List.of():
                List.of(span(clause.start()+relation.mode().start(),clause.start()+relation.mode().end()))));
        consumed.add(new Consumption("teaching_history_condition",clause,List.of(course.id)));return true;
    }
    private boolean history(Span clause) {
        String raw=clause.text();List<Target> titles=new ArrayList<>();Matcher title=TITLE.matcher(raw);
        while(title.find())titles.add(new Target(span(clause.start()+title.start(1),clause.start()+title.end(1)),clause.start()+title.start(),clause.start()+title.end()));
        Matcher reference=REFERENCE.matcher(raw);List<Span> refs=new ArrayList<>();
        while(reference.find())refs.add(span(clause.start()+reference.start(),clause.start()+reference.end()));
        // One predicate must have one target expression. Parallel declarations
        // are handled separately; no guessed precedence among multiple objects.
        if(titles.size()>1||refs.size()>1||!titles.isEmpty()&&!refs.isEmpty())return false;
        String skeleton=raw;String binding="implicit_single_course";List<MutableCourse> targetNodes=List.of();Target proposed=null;
        if(titles.size()==1) {
            proposed=titles.get(0);skeleton=replace(raw,clause,proposed.replaceStart(),proposed.replaceEnd());binding="named_course";
        } else if(refs.size()==1) {
            Span ref=refs.get(0);skeleton=replace(raw,clause,ref.start(),ref.end());
            boolean plural=!ref.text().matches(SINGULAR);
            if(plural) {
                if(focus.isEmpty() || !explicitPluralCount(ref.text(),focus.size()))return false;
                targetNodes=focus;binding="explicit_distributive_reference";
                if(!raw.substring(ref.end()-clause.start()).matches("^(?:均|都|分别).*"))return false;
            } else {if(focus.size()!=1)return false;targetNodes=focus;binding="singular_reference";}
        } else {
            Matcher plain=PLAIN_ACTION.matcher(raw);
            if(plain.matches()) {
                Span anchor=span(clause.start()+plain.start(2),clause.start()+plain.end(2));
                if(!simpleAnchor(anchor.text()))return false;
                proposed=new Target(anchor,anchor.start(),anchor.end());skeleton=replace(raw,clause,anchor.start(),anchor.end());binding="named_course";
            } else {if(focus.size()!=1)return false;targetNodes=focus;}
        }
        List<Span> audienceRefs=new ArrayList<>();Matcher audienceRef=AUDIENCE_REFERENCE.matcher(raw);
        while(audienceRef.find())audienceRefs.add(span(clause.start()+audienceRef.start(1),clause.start()+audienceRef.end(1)));
        if(!audienceRefs.isEmpty()&&audiences.stream().map(Span::text).distinct().count()!=1)return false;
        skeleton=skeleton.replaceAll("\\s+","").replaceFirst("的(?:老师|讲师|教师)$","");
        if(!audienceRefs.isEmpty())skeleton=AUDIENCE_REFERENCE.matcher(skeleton).replaceAll("");
        boolean regular=HISTORY.matcher(skeleton).matches();
        boolean completePast=COMPLETED_PAST.matcher(skeleton).matches() && skeleton.matches("(?s).*(?:已|曾|完成过|完成了).*" );
        boolean linkedRecord=LINKED_RECORD.matcher(skeleton).matches();
        boolean linkedAction=LINKED_ACTION.matcher(skeleton).matches()&&
                (skeleton.matches("(?s).*(?:已经|已|曾|过|了).*" )||skeleton.matches(".*(?:记录|经历)$"));
        if(!regular&&!completePast&&!linkedRecord&&!linkedAction)return false;
        if(proposed!=null)targetNodes=List.of(node(proposed.anchor()));
        if(targetNodes.isEmpty())return false;
        // Predicate flags come only from the syntax outside the replaced title.
        // A course named 《本人独立操作记录》 does not declare a personal actor.
        boolean personal=skeleton.matches("(?s).*(?:本人|亲自).*");
        // Past aspect establishes history, not completion of a whole course.
        boolean complete=skeleton.matches("(?s).*(?:讲完|完成).*" );
        HistoryCondition condition=new HistoryCondition(true,personal,complete,skeleton.contains("独立"),clause,binding,
                audienceRefs.isEmpty()?List.of():List.of(audiences.get(0)),audienceRefs);
        for(MutableCourse node:targetNodes) {node.history.add(condition);node.references.addAll(refs);}
        consumed.add(new Consumption("teaching_history_condition",clause,targetNodes.stream().map(n->n.id).toList()));
        if(proposed!=null)focus=targetNodes;
        return true;
    }
    private static String replace(String raw,Span clause,int start,int end){return raw.substring(0,start-clause.start())+MARK+raw.substring(end-clause.start());}
    private static boolean explicitPluralCount(String reference,int count) {
        Matcher number=Pattern.compile("([一二两三四五六七八九十0-9]+)门").matcher(reference);
        if(!number.find())return true;
        String value=number.group(1);int n;
        if(value.matches("[0-9]+")) {try{n=Integer.parseInt(value);}catch(NumberFormatException e){return false;}}
        else n=switch(value){case "一"->1;case "二","两"->2;case "三"->3;case "四"->4;case "五"->5;case "六"->6;case "七"->7;case "八"->8;case "九"->9;case "十"->10;default->-1;};
        return n==count;
    }
}
