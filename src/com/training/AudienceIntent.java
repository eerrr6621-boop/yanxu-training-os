package com.training;

import java.util.*;
import java.util.regex.*;

/** Demand intent, not a model or a job vocabulary. Future recipients remain
 * literal context; closed mandatory fit/history clauses retain hard evidence. */
final class AudienceIntent {
    private static final Pattern FIELD=Pattern.compile("(?:^|[\\r\\n；;])\\s*(?:授课对象|培训对象|参训对象|学员对象|受众)[:：]([^\\r\\n；;]+)");
    private static final Pattern RESTRICTION=Pattern.compile("必须|要求|需要|须|应当|只(?:能|要|接受|考虑|限)|仅(?:能|接受|限)|唯一|限于|排除|不接受|不考虑|不包括|不包含|不含|不得|不能|除外|但|并且|且|否则|如果|除非");
    private static final Pattern FIT=Pattern.compile("^(?:(?:本次|本|该|这门|这一门)?(?:课程|教学|培训)|客户|我们)?(?:必须|要求|需要|需|应|须)(?:能够)?(?:适配|适合|面向|针对)(.+)$");
    private static final Pattern NAMED_HISTORY=Pattern.compile("^(?:必须|要求|需要|须|应)(?:有|具备)(?:以前|曾经|曾|已)?(?:给|为|面向)(.+?)(?:主讲过|讲授过|讲过|教过)(?:此课|这门课|这门课程|该课|该课程|同一课程)(?:的)?(?:经历|记录)$");
    private static final Pattern COMPLETE_AUDIENCE_HISTORY=Pattern.compile("^(?:必须|要求|需要|须|需|应)(?:有|具备)(?:本人|自己)(?:面向|给|为)(.+?)(?:完整|独立完整)(?:授课|教学|讲授)(?:的)?(?:经历|记录)$");
    private static final Pattern NAMED_SUBJECT=Pattern.compile("^《([^《》]{2,80})》(.*)$");
    private static final Pattern STRONG_MODAL=Pattern.compile("必须|不得|不能|应当|须|只能|只接受|仅限|仅接受");
    private static final Pattern FUTURE_ACTIVITY=Pattern.compile("^(?:本次|这次|此次)?(.+?)(?:培训|课程|活动)(?:计划|准备|将要|要|将|拟)(?:加入|增加|开设|安排|开展|组织|举办)(.+)$");
    private static final Pattern FUTURE_BENEFIT=Pattern.compile("^(?:希望|旨在)(?:帮助|让)(.+?)(?:学会|了解|理解|掌握|识别|熟悉)(.+)$");
    private static final Pattern HISTORY_OR_CREDENTIAL=Pattern.compile("曾经|以前|过去|已经|已完成|讲过|教过|主讲过|讲授过|授课经历|教学经历|授课记录|资格|认证");
    record Context(String audience,int start,int end,List<String> courseIds) {}
    final String source;
    private final List<PostposedExclusion.Span> excluded;
    final List<Context> contexts=new ArrayList<>();
    private final Set<String> futureRecipientClauses=new HashSet<>();
    final LinkedHashMap<String,List<String>> hard=new LinkedHashMap<>();
    final List<String> unresolved=new ArrayList<>();

    AudienceIntent(String source,RequirementTopic.Result topics) {
        this.source=source;
        excluded=PostposedExclusion.spans(source);
        Matcher fields=FIELD.matcher(source);
        while(fields.find()) {
            if(PostposedExclusion.overlaps(excluded,fields.start(),fields.end()))continue;
            int start=fields.start(1),end=fields.end(1);
            while(start<end&&Character.isWhitespace(source.charAt(start)))start++;
            while(end>start&&(Character.isWhitespace(source.charAt(end-1))||source.charAt(end-1)=='。'))end--;
            add(source.substring(start,end),start,end,List.of());
        }
        for(RequirementTopic.Consumption c:topics.consumedSpans())if(c.kind().equals("narrative_audience")&&!PostposedExclusion.overlaps(excluded,c.span().start(),c.span().end()))
            add(c.span().text(),c.span().start(),c.span().end(),c.courseIds());
        // Only residual clauses are eligible. A literal course title or a
        // future declaration cannot manufacture a hard predicate from words.
        for(RequirementTopic.Unparsed residual:topics.unparsedClauses()) {
            if(PostposedExclusion.overlaps(excluded,residual.span().start(),residual.span().end()))continue;
            if(residual.reason().matches("delegated_field:(?:授课对象|培训对象|参训对象|学员对象|受众)"))continue;
            String clause=residual.span().text().strip().replaceFirst("^(?:师资要求|讲师要求|补充要求)[:：]\\s*","");
            // Free-text requests may state a future activity or its benefit
            // without a labelled audience field. Only the recipient span is
            // masked from legacy hard-feature scans; content and later clauses
            // stay intact. Qualified/history clauses are never partly consumed.
            addFutureRecipient(clause,residual);
            boolean named=false;
            if(clause.startsWith("《")) {
                Matcher subject=NAMED_SUBJECT.matcher(clause);
                if(!subject.matches()) {
                    if(STRONG_MODAL.matcher(clause).find())unresolved.add(residual.span().text());
                    continue;
                }
                String predicate=subject.group(2).strip();
                // A closed title alone is data, including any modal words
                // inside it. Only an external predicate can impose a demand.
                if(predicate.isEmpty()||!mandatoryClause(predicate)&&!STRONG_MODAL.matcher(predicate).find())continue;
                if(topics.courses().size()!=1||!topics.courses().get(0).canonicalAnchor().equals(subject.group(1))||
                    predicate.contains("《")||predicate.contains("》")) {
                    unresolved.add(residual.span().text());continue;
                }
                named=true;clause=predicate;
            }
            Matcher fit=FIT.matcher(clause),history=NAMED_HISTORY.matcher(clause),completeHistory=COMPLETE_AUDIENCE_HISTORY.matcher(clause);
            if(fit.matches()) {
                String target=target(fit.group(1));
                if(safe(target))hard.put("授课对象："+target,AudienceContract.terms(target));
                else unresolved.add(residual.span().text());
            } else if(history.matches()) {
                String target=history.group(1).strip();
                if(topics.courses().size()==1&&safe(target))hard.put("授课对象："+target,AudienceContract.terms(target));
                else unresolved.add(residual.span().text());
            } else if(completeHistory.matches()) {
                // A complete same-demand history clause already inspected by
                // RequirementConstraints. Do not add an unresolved duplicate
                // merely because it says “完整授课的经历”, not “讲过此课”.
                String target=completeHistory.group(1).strip();
                if(topics.courses().size()==1&&safe(target))hard.put("授课对象："+target,AudienceContract.terms(target));
                else unresolved.add(residual.span().text());
            } else if(named||mandatoryClause(clause)&&clause.matches("(?s).*(?:适配|适合|面向|针对|(?:给|为).*(?:讲过|教过|主讲过|讲授过)).*"))
                unresolved.add(residual.span().text());
        }
    }
    private void addFutureRecipient(String clause,RequirementTopic.Unparsed residual) {
        if(clause.contains("《")||clause.contains("》")||RESTRICTION.matcher(clause).find()||
            HISTORY_OR_CREDENTIAL.matcher(clause).find()||RequirementConstraints.NEGATION.matcher(clause).find()||
            RequirementConstraints.COMPARISON.matcher(clause).find())return;
        Matcher activity=FUTURE_ACTIVITY.matcher(clause),benefit=FUTURE_BENEFIT.matcher(clause);
        Matcher matched=activity.matches()?activity:benefit.matches()?benefit:null;
        if(matched==null)return;
        String audience=matched.group(1);
        if(!safe(audience))return;
        int local=residual.span().text().indexOf(clause);
        if(local<0)return;
        add(audience,residual.span().start()+local+matched.start(1),
            residual.span().start()+local+matched.end(1),List.of());
        futureRecipientClauses.add(clause);
    }
    boolean isFutureRecipientClause(String raw) {
        return futureRecipientClauses.contains(raw.strip().replaceFirst("^(?:师资要求|讲师要求|补充要求)[:：]\\s*",""));
    }
    private void add(String target,int start,int end,List<String> courses) {
        if(target.isBlank())return;
        if(!safe(target)) {
            hard.put("授课对象："+target,AudienceContract.terms(target));
            unresolved.add(target);return;
        }
        if(contexts.stream().noneMatch(c->c.start==start&&c.end==end))
            contexts.add(new Context(target,start,end,List.copyOf(courses)));
    }
    private static boolean safe(String target) {
        return !target.isBlank()&&target.length()<=240&&!RESTRICTION.matcher(target).find()&&
            !RequirementConstraints.COMPARISON.matcher(target).find()&&AudienceContract.typeComparable(AudienceContract.parse(target));
    }
    private static String target(String raw) {
        String value=raw.strip().replaceFirst("(?:的)?(?:开展)?(?:授课|教学|培训)$","");
        // A complete coordinated instructor-completion clause is not a second
        // audience. Its count/mode/history remain in the original query and
        // are still checked jointly by RequirementConstraints; only the
        // recipient phrase is returned here. Unknown tails are not trimmed.
        Matcher completed=Pattern.compile("^(.+?)、(?:已(?:经)?完成|完成过)"+RequirementConstraints.NUM+
            "(?:场|次|期)(?:面对面|面授|线下|线上|远程)?(?:工作坊|课程|课堂)(?:的)?(?:讲师|老师)$").matcher(value);
        if(completed.matches())value=completed.group(1);
        Matcher course=Pattern.compile("^(.+)的([^的]{1,60}(?:课程|课))$").matcher(value);
        return course.matches()?course.group(1):value;
    }
    static boolean mandatoryClause(String raw) {
        String clause=raw.strip().replaceFirst("^(?:师资要求|讲师要求|补充要求)[:：]\\s*","");
        if(clause.startsWith("《")) {
            Matcher subject=NAMED_SUBJECT.matcher(clause);
            if(!subject.matches())return STRONG_MODAL.matcher(clause).find();
            clause=subject.group(2).strip();
            if(clause.isEmpty())return false;
            if(STRONG_MODAL.matcher(clause).find())return true;
        }
        return clause.matches("^(?:另外|另|还|且|同时)?(?:(?:本次|本|该|这门|这一门)?(?:课程|教学|培训)|老师|讲师|教师|客户|我们)?(?:需要|要求|必须|应|需|须|希望).*" );
    }
    String featureText() {
        char[] text=PostposedExclusion.masked(source,excluded).toCharArray();
        for(Context c:contexts)Arrays.fill(text,c.start,c.end,' ');
        return new String(text);
    }
    Map<String,Object> audit(Context c) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("purpose","future_training_audience_context");result.put("requested_audience",c.audience);
        result.put("hard_requirement",false);result.put("course_ids",c.courseIds);
        result.put("requirement_matching_text",source);
        result.put("requirement_source_span",Map.of("text",source.substring(c.start,c.end),
            "source_start",source.codePointCount(0,c.start),"source_end",source.codePointCount(0,c.end),
            "offset_unit","unicode_code_point","source","requirement_matching_text_masked_operations"));
        result.put("normalization","LocalSemantic.requirementInput masks complete operational date/money fields with spaces; remaining text is unchanged. These are matching-text offsets, not original-field offsets.");
        result.put("confirmation","对象适配待确认");result.put("verified",false);
        return result;
    }
}
