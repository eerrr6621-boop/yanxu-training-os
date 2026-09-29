package com.training;

import java.util.*;
import java.util.regex.*;

/** Explicit constraints need source facts, not a high embedding score.
 * Unsupported/ambiguous exclusions remain review items, never silently pass. */
final class RequirementConstraints {
    static final String NUM="[零〇一二两三四五六七八九十百千\\d]+";
    // Commit to the complete action and its aspect marker. If no object
    // follows 教完过/教授过, regex backtracking must not invent 完过/授过.
    static final String TEACHING_OBJECT_VERB="(?>讲授|主讲(?!课程)|讲完|教完|教授|(?<!助)教(?!师|学|练|室|育|材|案))(?:过|了)?+";
    private static final Pattern AUDIENCE_FIELD=Pattern.compile("(?:^|[\\r\\n；;])\\s*(?:授课对象|培训对象|参训对象|学员对象|受众)[:：]([^\\r\\n；;]+)");
    // One longest-operator token owns its entire source span. Do not scan the
    // inside of 不少于 again as 少于, or the inside of 不低于 as 低于.
    static final Pattern COMPARISON=Pattern.compile("(不少于|不低于|不超过|不高于|不大于|不小于|未达到|没有达到|未满|不满|不到|至少|最多|至多|小于|大于|超过|少于|高于|低于|恰好|等于|达到|满|>=|<=|≥|≤|>|<|=)\\s*([+-]?"+NUM+"(?:(?:[.．点]|\\s*(?:至|到|[-~～—–])\\s*)"+NUM+")?)\\s*(分钟|小时|课时|个月|年|场|次|期|天|日|周|月|秒|分之)");
    private static final Set<String> LOWER_BOUND=Set.of("至少","不少于","不低于","达到","满","≥",">=");
    record Comparison(int start,int end,String operator,String value,String unit,boolean supported) {}
    final List<Comparison> comparisons=new ArrayList<>();
    static final Pattern NEGATION=Pattern.compile("不算|不计入|不计作|不作为|不满足|不接受|不考虑|不要|不需要|无需|不包含|不包括|不涉及|不能|不得|不是|并非|而非|没有|尚未|未实施|未授课|未讲授|排除");
    static final Pattern COMPLETED_COUNT=Pattern.compile("(?:已(?:经)?完成|完成过|已(?:经)?主讲|主讲过|讲授过|讲过)[^\\r\\n，,。；;]{0,12}?("+NUM+")\\s*(场|次|期)");
    static final Pattern EACH=Pattern.compile("每场|每次|每期|每门|每个模块|各至少|各不少于|均至少|分别至少|"+NUM+"(?:场|次|期)(?:都|均)");
    final LinkedHashMap<String,List<String>> features=new LinkedHashMap<>();
    final LinkedHashMap<String,List<String>> eachFeatures=new LinkedHashMap<>();
    final Set<String> resolvedWorkerExclusions=new HashSet<>();
    final List<Map<String,Object>> minimums=new ArrayList<>();
    final List<String> audienceReview=new ArrayList<>();
    final AudienceIntent audienceIntent;
    final String positive;
    final boolean teachingHistory;
    final boolean globalTeachingHistory;
    final boolean globalCompletedTeaching;
    final boolean unknownExclusion;
    final boolean distributedCounts;
    final boolean singleInstructor;
    final boolean qualificationRequired;

    RequirementConstraints(String raw) {
        this(raw,RequirementTopic.parse(raw),false);
    }
    RequirementConstraints(String raw,RequirementTopic.Result topics) {
        this(raw,topics,true);
    }
    private RequirementConstraints(String raw,RequirementTopic.Result topics,boolean boundCourseHistory) {
        String q=LocalSemantic.requirementInput(raw).replaceAll("[，,](都不能视为(?:已经)?讲过)","$1");
        List<PostposedExclusion.Span> postposed=PostposedExclusion.spans(q);
        String affirmativeView=PostposedExclusion.masked(q,postposed);
        StringBuilder teachingInput=new StringBuilder(affirmativeView);
        RequirementTopic.Result localTopics=topics.source().equals(q)?topics:RequirementTopic.parse(q);
        audienceIntent=new AudienceIntent(q,localTopics);
        features.putAll(audienceIntent.hard);
        String featureQuery=audienceIntent.featureText();
        // Recognised history belongs to its course node, not every course in
        // this demand. Topic names and future goal fields are not predicates.
        for(RequirementTopic.Consumption carried:localTopics.consumedSpans())
            if(carried.kind().equals("teaching_history_condition")||carried.kind().equals("course_declaration"))
                for(int at=carried.span().start();at<carried.span().end();at++)teachingInput.setCharAt(at,' ');
        for(RequirementTopic.Unparsed residual:localTopics.unparsedClauses())
            if(residual.reason().matches("delegated_field:(?:培训目标|希望解决的问题)"))
                for(int at=residual.span().start();at<residual.span().end();at++)teachingInput.setCharAt(at,' ');
        Matcher audienceSpan=AUDIENCE_FIELD.matcher(affirmativeView);
        while(audienceSpan.find()) for(int at=audienceSpan.start(1);at<audienceSpan.end(1);at++) teachingInput.setCharAt(at,' ');
        String teachingQuery=teachingInput.toString();
        StringBuilder bounded=new StringBuilder(q);
        Matcher m=COMPARISON.matcher(teachingQuery);
        boolean unknownComparison=false;
        while(m.find()) {
            int count=number(m.group(2));String unit=m.group(3);
            boolean supported=LOWER_BOUND.contains(m.group(1)) && count>=0 && count<=100000 &&
                    m.group(2).matches(NUM) && List.of("分钟","小时","课时","年","场","次","期").contains(unit) &&
                    (m.start()==0 || !q.substring(0,m.start()).matches("(?s).*(?:不|非|未)$"));
            comparisons.add(new Comparison(m.start(),m.end(),m.group(1),m.group(2),unit,supported));
            if(!supported) { unknownComparison=true;continue; }
            String clause=clauseAt(q,m.start());
            minimums.add(Map.of("minimum",count,"unit",unit,
                    "scope",EACH.matcher(clause).find()&&!List.of("场","次","期").contains(unit)?"each":"total"));
            for(int at=m.start();at<m.end();at++) bounded.setCharAt(at,' ');
        }
        // Delimited course names are objects, not negative conditions. Keep
        // guards outside a source-bound title, including unknown suffixes.
        for(RequirementTopic.Course course:localTopics.courses())for(RequirementTopic.Span anchor:course.anchorSpans())
            if(anchor.start()>0&&anchor.end()<q.length()&&HistoricalTeachingRequest.closing(q.charAt(anchor.start()-1))==q.charAt(anchor.end()))
                for(int at=anchor.start();at<anchor.end();at++)bounded.setCharAt(at,' ');
        String boundsMasked=bounded.toString();
        m=COMPLETED_COUNT.matcher(teachingQuery);
        while(m.find()) {
            int count=number(m.group(1));String unit=m.group(2);
            if(minimums.stream().noneMatch(b->Objects.equals(b.get("minimum"),count)&&Objects.equals(b.get("unit"),unit)))
                minimums.add(Map.of("minimum",count,"unit",unit,"scope","total"));
        }
        singleInstructor=Arrays.stream(affirmativeView.split("[\\r\\n，,。；;]+" )).anyMatch(RequirementConstraints::knownSingleInstructor);
        qualificationRequired=affirmativeView.matches("(?s).*(?:已经具备|已具备|已取得|已获得|需具备|须具备|具有|持有).{0,14}(?:授课资格|讲师资格|资格证|认证证书).*");
        globalTeachingHistory=(!boundCourseHistory&&localTopics.courses().stream().anyMatch(RequirementTopic.Course::requiresHistory))||teachingHistoryRequirement(teachingQuery) ||
                minimums.stream().anyMatch(r->List.of("年","场","次","期").contains(r.get("unit"))) ||
                Arrays.stream(affirmativeView.split("[\\r\\n，,。；;]+" )).anyMatch(RequirementConstraints::knownRoleExclusion);
        globalCompletedTeaching=completedTeachingRequirement(teachingQuery);
        teachingHistory=globalTeachingHistory||localTopics.courses().stream().anyMatch(RequirementTopic.Course::requiresHistory);
        // These are declared delivery conditions, not assumptions about capability.
        require(featureQuery,"中文授课","中文","中文|汉语");
        require(featureQuery,"英文授课","英文","英文|英语");
        require(featureQuery,"老年学员","老年|长辈|银龄|养老诈骗","老年|长辈|银龄|养老");
        require(featureQuery,"零基础教学","零基础","零基础|入门");
        require(featureQuery,"行政人员","行政人员|行政办公","行政");
        require(featureQuery,"现场角色演练","角色演练","角色演练|角色扮演|情景模拟");
        if(learnerCompletion(featureQuery)) features.put("现场完成练习",List.of("现场完成练习"));
        require(featureQuery,"逐项点评","逐项点评","逐项点评|逐一点评");
        require(featureQuery,"线上实时教学","线上实时|线上直播","线上直播|线上实时|直播");
        List<String[]> clauses=new ArrayList<>();
        Matcher clauseMatcher=Pattern.compile("[^\\r\\n，,。；;]+").matcher(q);
        while(clauseMatcher.find()) clauses.add(new String[]{clauseMatcher.group(),boundsMasked.substring(clauseMatcher.start(),clauseMatcher.end()),featureQuery.substring(clauseMatcher.start(),clauseMatcher.end()),affirmativeView.substring(clauseMatcher.start(),clauseMatcher.end())});
        String affirmative=clauses.stream().filter(c->!NEGATION.matcher(c[1]).find()).map(c->c[2]).reduce("",(a,b)->a+"；"+b);
        require(affirmative,"面对面授课形式","面对面|线下|面授","面对面|线下|面授");
        if(completedTeachingRequirement(affirmative) && affirmative.contains("现场"))
            features.put("面对面授课形式",List.of("面对面","线下","面授","现场教学","现场授课","现场主讲","现场完成教学","现场完成过教学"));
        require(affirmative,"远程授课形式","远程|线上|在线教学|直播","远程|线上|在线教学|直播");
        require(featureQuery,"实时演示","实时演示","实时演示");
        require(featureQuery,"互动练习","互动练习","互动练习|互动实操");
        require(featureQuery,"门店场景","零售门店|门店费用","零售门店|门店费用");
        require(featureQuery,"课堂案例","主讲案例","案例");
        // Preserve explicit delivery requirements beyond the small synonym map.
        Matcher language=Pattern.compile("(?:必须|要求|需|应)(?:使用|采用|用)?([\\p{IsHan}]{1,6}(?:语|文))(?:进行)?(?:授课|教学|讲授)").matcher(featureQuery);
        while(language.find()) {
            String value=language.group(1);
            features.put("授课语言："+value, value.matches("英文|英语")?List.of("英文","英语"):value.matches("中文|汉语")?List.of("中文","汉语"):List.of(value));
        }
        Matcher audienceField=AUDIENCE_FIELD.matcher(affirmativeView);
        while(audienceField.find()) {
            // Preserve the whole field, including parenthetical qualifications,
            // conjuncts and exclusions. An unproved qualifier must stay pending.
            String target=audienceField.group(1).strip().replaceFirst("[。]+$","");
            if(!balancedAudienceQualifier(target)) {
                // A field separator inside parentheses is not permission to
                // accept a truncated qualifier. Preserve the complete line
                // for review instead of claiming to parse nested field syntax.
                int end=audienceField.end(1);
                while(end<q.length()&&q.charAt(end)!='\n'&&q.charAt(end)!='\r') end++;
                audienceReview.add(q.substring(audienceField.start(),end).strip());
            }
        }
        boolean unknown=!postposed.isEmpty() || unknownComparison || !audienceReview.isEmpty() || !audienceIntent.unresolved.isEmpty() || clauses.stream().map(c->c[0]).anyMatch(c->
                c.contains("现场完成") && !completedTeachingRequirement(c) && !learnerCompletion(c));
        distributedCounts=EACH.matcher(teachingQuery).find();
        if(distributedCounts) {
            if(affirmativeView.matches("(?s).*(?:练习|演练|实操).*")) eachFeatures.put("每场均有练习",List.of("练习","演练","实操"));
            if(affirmativeView.matches("(?s).*(?:反馈|点评).*")) eachFeatures.put("每场均有反馈",List.of("反馈","点评"));
            if(eachFeatures.isEmpty() && minimums.stream().noneMatch(b->"each".equals(b.get("scope")))) unknown=true;
        }
        List<String> positives=new ArrayList<>();
        for(String[] queryClause:clauses) {
            String clause=queryClause[0];
            clause=clause.strip();if(clause.isEmpty()) continue;
            // Numeric lower bounds contain 不 but are positive, enforceable facts.
            String withoutBounds=queryClause[1].strip()
                    .replaceFirst("^不是(?:系统指令|系统消息|操作指令|系统命令|提示词)[:：]?","");
            if(NEGATION.matcher(withoutBounds).find()) {
                boolean roleExclusion=knownRoleExclusion(clause);
                boolean modeExclusion=redundantRecordedExclusion(clause,localTopics);
                if(modeExclusion)resolvedWorkerExclusions.add(clause);
                if(roleExclusion && teachingHistory) {
                    resolvedWorkerExclusions.add(clause);
                    int suffix=clause.indexOf("都不能视为");
                    // The worker splits at commas; retain a suffix only after
                    // its complete antecedent role list was independently parsed.
                    if(suffix>0) resolvedWorkerExclusions.add(clause.substring(suffix));
                }
                // Even familiar role exclusions require affirmative personal
                // teaching evidence; other negative preferences remain review.
                if(!knownSingleInstructor(clause) && !modeExclusion && (!roleExclusion || !teachingHistory)) unknown=true;
            } else if(!queryClause[3].isBlank()) positives.add(clause);
        }
        positive=String.join("；",positives);
        unknownExclusion=unknown;
    }
    static boolean completedTeachingRequirement(String text) {
        return text.matches("(?s).*(?:本人|老师|讲师|教师)(?:(?:已(?:经)?|实际|曾(?:经)?|亲自|必须|需要|应|须|在|现场|当场)){0,7}完成(?:过|了)?(?:教学|授课|讲授).*" ) ||
                text.matches("(?s).*(?:本人|老师|讲师|教师)(?:(?:已(?:经)?|实际|曾(?:经)?|亲自|独立|必须|需要|应|须)){0,7}讲完(?:过|了)?[^\\r\\n，,。；;]{2,80}(?:课堂记录|教学记录|授课记录|课程记录|授课经历).*" );
    }
    static boolean knownExclusiveDelivery(String clause) {
        // Complete, closed delivery clauses already routed to typed features.
        // A partial feature match cannot discharge an additional restriction.
        return clause.strip().matches("(?:只能|仅能|只接受|仅接受|仅限)(?:(?:中文|英文)(?:授课|教学|讲授)|(?:面对面|线下|面授|远程|线上)(?:授课|教学|讲授)?)");
    }
    static boolean teachingHistoryRequirement(String text) {
        return text.matches("(?s).*(?:实际授课|实际教学|授课记录|课堂记录|教学记录|对应授课经历|本人主讲|个人经历|亲自主讲|已经讲过|(?:主讲|讲授|教)(?:过|了)|独立讲授).*") ||
                completedTeachingRequirement(text) || text.matches("(?s).*(?:必须|需要|要求).*(?:讲过|主讲过|实际授课).*") ||
                text.matches("(?s).*(?:实际|明确记载|已经讲过|主讲过|讲授过).*(?:授课|课堂|教学|讲过|经历|记录).*" );
    }
    private static boolean balancedAudienceQualifier(String text) {
        int depth=0;
        for(char ch:LocalSemantic.normalized(text).toCharArray()) {
            if(ch=='(') depth++;
            else if(ch==')'&&--depth<0) return false;
        }
        return depth==0;
    }
    private static final Pattern LEARNER_PRACTICE=Pattern.compile("(?:学员|学生|学习者|参训人员|参与者)(?:们)?(?:(?:须|需|必须|能够|能|均|都|各自|在|独立|实际)){0,5}现场(?:独立|实际)?完成(?:过|了)?(?:一项|对应的?|本次|规定的?|指定的?|课堂)?练习(?=$|[，,。；;\\r\\n])");
    static boolean learnerCompletion(String text) {
        // The learner, not the instructor, performs a practice/artifact task.
        // 完成教学 describes teaching history and must not invent a practice.
        return LEARNER_PRACTICE.matcher(text).find();
    }
    private static boolean learnerPracticeScoped(String text,Map<String,List<String>> facets) {
        Matcher action=LEARNER_PRACTICE.matcher(text);
        while(action.find()) if(featureScoped(text,facets,List.of(action.group()))) return true;
        return false;
    }
    static boolean knownRoleExclusion(String clause) {
        return clause.strip().matches("^(?:(?:本|所在)?(?:单位|机构|公司|中心|团队))(?:办过班|办班|开过课|开课|组织培训|培训项目)(?:不能代替|不等于|不代表)(?:他)?(?:本人|个人)(?:的)?(?:授课|教学)?(?:经历|记录)$") ||
                clause.strip().matches("^(?:仅|只|单)(?:有|做|做过)?(?:写教案|编写教案|备课|写讲义)(?:的)?(?:成果|经历|记录)?(?:还)?(?:不够|不足)$") ||
                clause.strip().matches("^(?:(?:所在|本)?机构(?:的)?客户名单)(?:不算|不作为|不能代替)(?:个人(?:授课)?经历|本人(?:授课)?经历|个人记录)$") ||
                clause.strip().matches("^(?:(?:仅|只)(?:参加|负责)?(?:参训|旁听|助教|会务|教材编写|计划授课))(?:经历|记录)?(?:不算|不计入|不能代替)(?:实际授课|授课记录|授课经历|个人授课经历)$") ||
                explicitRoleSubstitution(clause);
    }
    private static boolean redundantRecordedExclusion(String clause,RequirementTopic.Result topics) {
        // Recording-only evidence cannot satisfy an already source-bound
        // completed personal onsite history requirement. This is not a ban on
        // teachers who also have separate recording experience.
        if(!clause.matches("(?:只|仅)(?:做过|有)(?:在线录屏|线上录播|录屏|录播)(?:讲解|授课|教学)(?:经历)?(?:不满足|不算)(?:这次条件|本次要求|所需现场面授经历)"))return false;
        if(topics.courses().size()!=1)return false;
        return topics.courses().get(0).historyConditions().stream().anyMatch(h->h.personal()&&h.completed()&&h.pastTeaching()&&
            h.modeBindings().stream().anyMatch(s->HistoricalTeachingRequest.modeLabel(s.text()).equals("面对面授课形式")));
    }
    private static String audienceTarget(String text) {
        String value=text.strip().replaceFirst("(?:的)?(?:开展)?(?:授课|教学|培训)$","");
        // The final 的 + closed course noun is a teaching object; an internal
        // 的 in 新入职的讲解员 or a qualification must remain in the audience.
        Matcher course=Pattern.compile("^(.+)的([^的]{1,60}(?:课程|课))$").matcher(value);
        return course.matches()?course.group(1):value;
    }
    /** One shared typed audience contract for field and narrative entry points. */
    static List<String> audienceTerms(String target) {
        return AudienceContract.terms(target);
    }
    static boolean explicitRoleSubstitution(String clause) {
        String[] halves=clause.strip().replaceAll("[，,](都不能视为)","$1").split("(?:都不能视为|不能替代|不能代替|不等于|不代表|不是|不算|不计入)",2);
        if(halves.length!=2 || !halves[1].matches("(?:本人|个人|对应)?(?:的)?(?:已(?:经)?讲(?:过)?|实际(?:授课|教学)|授课|教学)?(?:经历|经验|记录|授课|已讲)?")) return false;
        for(String part:halves[0].split("、|以及|和|与|及|或"))
            if(!part.strip().matches("(?:仅|只|只有|仅有|只做过|仅参加|参加|担任|做过|取得|获得)?(?:培训助教|助教|会务|参训|旁听|资格证|资格证书|认证|认证培训|认证证书|筹备|筹备课程|课程筹备|计划授课|正在筹备讲师资格)(?:的)?(?:经历|经验|记录)?")) return false;
        return !halves[1].isBlank();
    }
    static boolean knownSingleInstructor(String clause) {
        return clause.strip().matches("(?:不能|不得)(?:拆成|拆给|分给)(?:几个人|多个人|多人|多个老师|多个讲师)(?:拼课|授课|讲授)") ||
                clause.strip().matches("(?:要求|需要|必须)?(?:由)?同一(?:位|个)?(?:老师|讲师)(?:独立)?(?:完整|全部)?覆盖(?:所有|全部|四个|四项|各个)?(?:模块|课程)");
    }
    boolean handlesWorkerExclusions(Map<?,?> semantic) {
        if(!Boolean.TRUE.equals(semantic.get("query_requires_review"))) return true;
        if(!(semantic.get("query_exclusions") instanceof List<?> exclusions) || exclusions.isEmpty()) return false;
        return exclusions.stream().allMatch(e->e instanceof String &&
                (teachingHistory&&(knownRoleExclusion((String)e)||resolvedWorkerExclusions.contains(((String)e).strip())) ||
                 singleInstructor&&knownSingleInstructor((String)e)));
    }
    private static String clauseAt(String text,int position) {
        int start=position,end=position;
        while(start>0&&"\r\n，,。；;".indexOf(text.charAt(start-1))<0) start--;
        while(end<text.length()&&"\r\n，,。；;".indexOf(text.charAt(end))<0) end++;
        return text.substring(start,end);
    }
    private void require(String q,String label,String trigger,String accepted) {
        if(Pattern.compile(trigger).matcher(q).find()) features.put(label,List.of(accepted.split("\\|")));
    }
    static int number(String value) {
        try { return Integer.parseInt(value); } catch(NumberFormatException ignored) {}
        // Chinese digit-by-digit dates/ambiguous shorthand are not durations.
        if(value.length()>1 && !value.matches(".*[十百千].*")) return -1;
        String digits="零一二三四五六七八九";int total=0,current=0;
        for(char c:value.replace('两','二').replace('〇','零').toCharArray()) {
            int d=digits.indexOf(c);if(d>=0) current=d;
            else if(c=='十'||c=='百'||c=='千') { total+=(current==0?1:current)*(c=='十'?10:c=='百'?100:1000);current=0; }
            else return -1;
        }
        return total+current;
    }
    static boolean hasCount(String evidence,String unit,int minimum) {
        return hasCount(evidence,unit,minimum,false);
    }
    private static boolean hasCount(String evidence,String unit,int minimum,boolean personalTeachingContext) {
        if(minimum<0 || minimum>100000) return false;
        if(evidence.matches("(?s).*"+NUM+"分之"+NUM+"\\s*(?:分钟|小时|课时|年|场|次|期).*")) return false;
        // No summing unrelated sessions or translating undefined lesson-hours.
        if(evidence.matches("(?s).*(?:休息|午餐|茶歇|通勤|交通|候车).*")) return false;
        Matcher ranges=Pattern.compile("("+NUM+")\\s*(?:分钟|小时|课时|年|场|次|期)?\\s*(?:至|到|[-–—~～.．点])\\s*("+NUM+")\\s*(分钟|小时|课时|年|场|次|期)").matcher(evidence);
        while(ranges.find()) {
            int first=number(ranges.group(1)),last=number(ranges.group(2));
            if(!"年".equals(ranges.group(3)) || first<1900 || last<1900) return false;
        }
        Matcher m=Pattern.compile("(?<![\\d.．–—~～至到-])("+NUM+")(?![\\d.])\\s*("+("分钟".equals(unit)?"分钟|小时":"小时".equals(unit)?"小时|分钟":List.of("场","次","期").contains(unit)?"场|次|期":Pattern.quote(unit))+")").matcher(evidence);
        while(m.find()) {
            int count=number(m.group(1));String actual=m.group(2);
            int left=m.start(),right=m.end();
            while(left>0 && "，,。；;\r\n".indexOf(evidence.charAt(left-1))<0) left--;
            while(right<evidence.length() && "，,。；;\r\n".indexOf(evidence.charAt(right))<0) right++;
            String clause=evidence.substring(left,right);
            if(Pattern.compile("(?:约为|大约|大概|大致|将近|不足|约|近)\\s*"+NUM+"|"+NUM+"\\s*(?:分钟|小时|课时|年|场|次|期)(?:左右|上下)").matcher(clause).find()) continue;
            if(clause.matches("(?s).*(?:休息|午餐|茶歇|通勤|交通|候车|会议|岗位|从业|参训|旁听|计划|拟).*") ) continue;
            if(!clause.matches("(?s).*(?:授课|讲授|主讲|教学|课堂|工作坊|课长|课程时长|累计|共计).*") &&
                    !(personalTeachingContext && List.of("场","次","期").contains(unit) && clause.contains("完成"))) continue;
            if("年".equals(unit)) {
                if(count<0 || count>100) continue; // 2025年 is a date, not experience.
                String around=evidence.substring(Math.max(0,m.start()-18),Math.min(evidence.length(),m.end()+25));
                if(!personalTeachingContext && !around.matches("(?s).*(?:授课|讲授|主讲|教学).*")) continue;
                if(clause.matches("(?s).*(?:从业|岗位|入职|任职).*")) continue;
            }
            if(count<0) continue;
            if("分钟".equals(unit) && "小时".equals(actual)) count*=60;
            if("小时".equals(unit) && "分钟".equals(actual)) { if(count>=minimum*60) return true;continue; }
            if(count>=minimum) return true;
        }
        return false;
    }
    static boolean topicRelated(String text,Map<String,List<String>> facets) {
        return facets.isEmpty() || facets.values().stream().anyMatch(terms->RequirementCoverage.contains(text,terms));
    }
    static boolean topicCount(String text,String unit,int minimum,Map<String,List<String>> facets) {
        if(!hasCount(text,unit,minimum,true)) return false;
        if(facets.isEmpty()) return true;
        String anchored=anchoredEventHead(text,facets);
        if(!anchored.isEmpty() && hasCount(anchored,unit,minimum,true)) return true;
        boolean context=false;
        for(String clause:text.split("[\\r\\n，,。；;]+")) {
            if(topicRelated(clause,facets)) context=true;
            else {
                // Only an unambiguous duration/count continuation can inherit
                // the preceding topic. A new, even unknown, subject cannot.
                String residual=clause.replaceAll(NUM+"\\s*(?:分钟|小时|课时|年|场|次|期)","")
                    .replaceAll("(?:本人|个人|实际|已经|已|曾|累计|共计|总计|共|完成|持续|对应|授课|讲授|主讲|教学|课堂|课程时长|课长|时长|经历|记录|为|达|计|仅|有|：|:|\\s)","");
                if(!residual.isEmpty()) context=false;
            }
            if(context && hasCount(clause,unit,minimum,true)) return true;
        }
        return false;
    }
    static boolean featureScoped(String text,Map<String,List<String>> facets,List<String> accepted) {
        if(facets.isEmpty()) return featureClaim(text,accepted);
        String anchored=anchoredEventHead(text,facets);
        if(!anchored.isEmpty() && featureClaim(anchored,accepted)) return true;
        boolean context=false;
        for(String clause:text.split("[\\r\\n，,。；;]+")) {
            clause=clause.replaceFirst("^(?:并且|并|且)","");
            boolean related=topicRelated(clause,facets);
            if(related) context=true;
            // A labelled audience field can continue the current course scope;
            // it supplies an audience, not personal completed teaching history.
            else if(!countContinuation(clause) && (!clause.matches("^(?:授课语言|教学语言|授课对象|培训对象|学员对象|受众[:：]|该课程|本课程|授课采用|采用|使用|带领学员|组织学员|学员|课堂|现场|逐项|逐一|全程|面向|对象|每场|每次|每期|均|"+NUM+"份活动记录|两场|两次|两期|对.{0,12}(?:成果|练习|作品|作业)).*") ||
                    clause.matches("(?s).*(?:另|其他|主讲|讲授|课程[:：《]).*"))) context=false;
            if(context && featureClaim(clause,accepted)) return true;
        }
        return false;
    }
    private static boolean featureClaim(String text,List<String> terms) {
        // Even an exact legacy phrase cannot certify that a previously taught
        // cohort is the same identified group requested now.
        if(terms instanceof AudienceContract.Terms audience&&!AudienceContract.typeComparable(audience.contract))return false;
        String declared=terms instanceof AudienceContract.Terms?AudienceContract.declaredValue(text):null;
        boolean content=declared!=null?AudienceContract.compare(((AudienceContract.Terms)terms).contract,AudienceContract.parse(declared)).supported():RequirementCoverage.contains(text,terms);
        if(!content || ProfessionalEvidence.negative(text) ||
                text.matches("(?s).*(?:不含|不面向|不针对|不采用|未覆盖|未安排|未组织|未完成|取消|待补充|待开展|待安排|尚待|计划|拟安排|将安排).*") ) return false;
        for(String term:terms) if(text.contains("非"+term) || text.contains(term+"除外")) return false;
        return true;
    }
    private static boolean countContinuation(String clause) {
        return clause.matches("(?s).*"+NUM+"\\s*(?:分钟|小时|课时|年|场|次|期).*") &&
                clause.replaceAll(NUM+"\\s*(?:分钟|小时|课时|年|场|次|期)","")
                    .replaceAll("(?:本人|个人|实际|已经|已|曾|累计|共计|总计|共|完成|持续|对应|授课|讲授|主讲|教学|课堂|课程时长|课长|时长|经历|记录|为|达|计|仅|有|：|:|\\s)","").isEmpty();
    }
    private static String anchoredEventHead(String text,Map<String,List<String>> facets) {
        String[] clauses=text.split("[\\r\\n，,。；;]+",3);
        if(clauses.length<2 || !clauses[1].matches("^(?:培训主题|主题)(?:都)?(?:是|为|[:：]).*") ||
                !topicRelated(clauses[1],facets) || RequirementCoverage.otherTopic(clauses[0],facets.keySet())) return "";
        return clauses[0].matches("^(?:本人|我|该讲师|该教师)(?:已|已经|曾|实际)(?:为|面向).{1,30}完成.{1,40}(?:工作坊|授课|课堂|教学).*")
                ?clauses[0]:"";
    }
    List<Map<String,Object>> assess(List<Map<String,Object>> records,Map<String,List<String>> facets) {
        return assess(records,facets,false);
    }
    /** This audit is deliberately separate from assess()'s hard-check list. */
    List<Map<String,Object>> audienceContext(List<Map<String,Object>> records,Map<String,List<String>> facets,Map<String,Set<String>> courseScopes) {
        List<Map<String,Object>> result=new ArrayList<>();
        for(AudienceIntent.Context context:audienceIntent.contexts) {
            Map<String,List<String>> scope=new LinkedHashMap<>();
            if(context.courseIds().isEmpty())scope.putAll(facets);
            else for(String id:context.courseIds())for(String key:courseScopes.getOrDefault(id,Set.of()))
                if(facets.containsKey(key))scope.put(key,facets.get(key));
            String proof="",evidenceId="";boolean history=false;
            for(Map<String,Object> record:records) {
                String text=Objects.toString(record.get("text"),"");
                boolean supported=!scope.isEmpty()&&(TeachingEvents.structured(record)?
                    scope.values().stream().allMatch(terms->TeachingEvents.supportsTopic(record,terms))&&TeachingEvents.supportsFeature(record,"授课对象："+context.audience(),audienceTerms(context.audience())):
                    topicRelated(text,scope)&&(ProfessionalEvidence.grade(text)>0||"teaching_history_statement".equals(record.get("role")))&&featureScoped(text,scope,audienceTerms(context.audience())));
                if(!supported)continue;
                if(proof.isEmpty()){proof=text;evidenceId=Objects.toString(record.get("evidence_id"),"");}
                if("teaching_history_statement".equals(record.get("role")))history=true;
            }
            Map<String,Object> audit=audienceIntent.audit(context);
            audit.put("source_audience_type_supported",!proof.isEmpty());
            audit.put("source_audience_experience_stated",history);
            audit.put("evidence_id",evidenceId);audit.put("evidence",proof.substring(0,Math.min(220,proof.length())));
            result.add(audit);
        }
        return result;
    }
    List<Map<String,Object>> assess(List<Map<String,Object>> records,Map<String,List<String>> facets,boolean perContentFeatures) {
        return assess(records,facets,perContentFeatures,(record,scope)->true);
    }
    List<Map<String,Object>> assess(List<Map<String,Object>> records,Map<String,List<String>> facets,boolean perContentFeatures,
            java.util.function.BiPredicate<Map<String,Object>,Map<String,List<String>>> courseCondition) {
        return assess(records,facets,perContentFeatures,courseCondition,records);
    }
    List<Map<String,Object>> assess(List<Map<String,Object>> records,Map<String,List<String>> facets,boolean perContentFeatures,
            java.util.function.BiPredicate<Map<String,Object>,Map<String,List<String>>> courseCondition,
            List<Map<String,Object>> qualificationRecords) {
        List<Map<String,Object>> checks=new ArrayList<>();
        if(globalCompletedTeaching)for(Map<String,List<String>> scope:atomicScopes(facets)) {
            String proof="";
            for(Map<String,Object> record:records) {
                if(!courseCondition.test(record,scope)||!"teaching_history_statement".equals(record.get("role")))continue;
                if(TeachingEvents.structured(record)) {
                    if("past_teaching".equals(TeachingEvents.metadata(record).get("completion_scope"))||
                        scope.values().stream().anyMatch(terms->!TeachingEvents.supportsTopic(record,terms)))continue;
                }else if(!topicRelated((String)record.get("text"),scope))continue;
                proof=(String)record.get("text");break;
            }
            checks.add(sourcedCheck("本人完成教学的来源依据"+scopeLabel(scope,facets),proof,records));
        }
        for(Map.Entry<String,List<String>> feature:features.entrySet()) {
          for(Map<String,List<String>> scope:perContentFeatures?atomicScopes(facets):List.of(facets)) {
            String proof="";
            for(Map<String,Object> record:records) {
                if(!courseCondition.test(record,scope))continue;
                String text=(String)record.get("text");
                if(TeachingEvents.structured(record)) {
                    if(scope.values().stream().allMatch(terms->TeachingEvents.supportsTopic(record,terms))&&
                        (!globalTeachingHistory||"teaching_history_statement".equals(record.get("role")))&&TeachingEvents.supportsFeature(record,feature.getKey(),feature.getValue())){proof=text;break;}
                    continue;
                }
                if(!topicRelated(text,scope) || ProfessionalEvidence.grade(text)==0 && !"teaching_history_statement".equals(record.get("role"))) continue;
                if(globalTeachingHistory && !"teaching_history_statement".equals(record.get("role"))) continue;
                if(feature.getKey().equals("现场完成练习")) {
                    if(learnerPracticeScoped(text,scope)) { proof=text;break; }
                    continue;
                }
                if(InstructorMode.applies(feature.getKey())?InstructorMode.prose(text,feature.getKey(),scope):featureScoped(text,scope,feature.getValue())) { proof=text;break; }
            }
            Map<String,Object> featureCheck=sourcedCheck(feature.getKey()+(perContentFeatures?scopeLabel(scope,facets):""),proof,records);
            if(feature.getValue() instanceof AudienceContract.Terms audience) {
                featureCheck.put("audience_requirement_contract",audience.contract.map());
                final String audienceProof=proof;
                List<Map<String,Object>> bound=records.stream().filter(r->TeachingEvents.structured(r)&&Objects.equals(audienceProof,r.get("text"))).toList();
                if(!proof.isEmpty()&&bound.size()==1)featureCheck.put("audience_comparison",AudienceContract.compare(audience.contract,AudienceContract.parse(TeachingEvents.field(bound.get(0),"audience"))).map());
            }
            checks.add(featureCheck);
          }
        }
        for(Map<String,Object> bound:minimums) {
            String unit=(String)bound.get("unit");int minimum=(Integer)bound.get("minimum");String proof="";
            if("each".equals(bound.get("scope"))) {
                for(Map<String,List<String>> scope:atomicScopes(facets)) {
                    proof="";
                    for(Map<String,Object> record:records) if(courseCondition.test(record,scope)&&(TeachingEvents.structured(record)?
                        "teaching_history_statement".equals(record.get("role"))&&scope.values().stream().allMatch(terms->TeachingEvents.supportsTopic(record,terms))&&TeachingEvents.supportsBound(record,unit,minimum,true):
                        historyRecord(record,scope) && eachBound((String)record.get("text"),unit,minimum,scope))) {
                        proof=(String)record.get("text");break;
                    }
                    checks.add(sourcedCheck("逐场／逐课至少"+minimum+unit+"（不能用累计数代替）"+scopeLabel(scope,facets),proof,records));
                }
                continue;
            }
            for(Map<String,Object> record:records) {
                if(!courseCondition.test(record,facets))continue;
                String text=(String)record.get("text");
                if(TeachingEvents.structured(record)) {
                    if("teaching_history_statement".equals(record.get("role"))&&facets.values().stream().allMatch(terms->TeachingEvents.supportsTopic(record,terms))&&
                        TeachingEvents.supportsBound(record,unit,minimum,false)){proof=text;break;}continue;
                }
                if(!topicRelated(text,facets) || !"teaching_history_statement".equals(record.get("role"))) continue;
                if(!facets.isEmpty() && RequirementCoverage.otherTopic(text,facets.keySet())) continue;
                if(text.matches("(?s).*(?:团队|机构累计|公司累计|旁听|参训|教材编写).*")) continue;
                if(topicCount(text,unit,minimum,facets)) { proof=text;break; }
            }
            checks.add(sourcedCheck("至少"+minimum+unit+"（对应个人授课证据）",proof,records));
        }
        for(Map.Entry<String,List<String>> feature:eachFeatures.entrySet()) {
            for(Map<String,List<String>> scope:atomicScopes(facets)) {
                String proof="";
                for(Map<String,Object> record:records) if(courseCondition.test(record,scope)&&(TeachingEvents.structured(record)?
                    "teaching_history_statement".equals(record.get("role"))&&scope.values().stream().allMatch(terms->TeachingEvents.supportsTopic(record,terms))&&
                    RequirementCoverage.contains(TeachingEvents.field(record,"each_activity"),feature.getValue()):
                    historyRecord(record,scope) && eachFeature((String)record.get("text"),scope,feature.getValue()))) { proof=(String)record.get("text");break; }
                checks.add(sourcedCheck(feature.getKey()+"（不能用一次或累计活动代替）"+scopeLabel(scope,facets),proof,records));
            }
        }
        // Count, mode, audience and per-session activities must describe one
        // evidenced completed event set, not unrelated records on the same topic.
        boolean eventSet=minimums.stream().anyMatch(b->List.of("场","次","期").contains(b.get("unit")) && (Integer)b.get("minimum")>1);
        if(eventSet && (!features.isEmpty() || !eachFeatures.isEmpty() || minimums.stream().anyMatch(b->"each".equals(b.get("scope"))))) {
            String proof="";
            for(Map<String,Object> record:records) if(courseCondition.test(record,facets)&&(TeachingEvents.structured(record)?structuredJoint(record,facets):
                historyRecord(record,facets) && jointEventSet((String)record.get("text"),facets))) {
                proof=(String)record.get("text");break;
            }
            checks.add(sourcedCheck("完成次数、形式、对象及逐场环节须绑定同一组本人授课记录",proof,records));
        }
        if(records.stream().anyMatch(TeachingEvents::structured)&&(!features.isEmpty()||!minimums.isEmpty()||!eachFeatures.isEmpty())) {
            for(Map<String,List<String>> scope:atomicScopes(facets)) {
                String proof="";
                for(Map<String,Object> record:records) {
                    if(!courseCondition.test(record,scope))continue;
                    boolean supported=TeachingEvents.structured(record)?structuredJoint(record,scope):
                        (!globalTeachingHistory||"teaching_history_statement".equals(record.get("role")))&&jointEventSet((String)record.get("text"),scope);
                    if(supported){proof=(String)record.get("text");break;}
                }
                checks.add(sourcedCheck("同一课程事件内的对象、形式与数量完整关联"+scopeLabel(scope,facets),proof,records));
            }
        }
        if(qualificationRequired) {
            checks.add(QualificationClaim.check(qualificationRecords,facets));
        }
        for(String field:audienceReview) checks.add(Map.of("criterion","授课对象限定未完整解析，须核对完整字段",
                "status","needs_evidence","evidence","","verified",false,"requirement_text",field));
        for(String clause:new LinkedHashSet<>(audienceIntent.unresolved))checks.add(Map.of(
                "criterion","对象适配、经历或排除要求未完整承载，须核对原文","status","needs_evidence",
                "evidence","","verified",false,"requirement_text",clause));
        if(unknownExclusion) checks.add(check("未确认的硬性条件需人工核对",""));
        return checks;
    }
    private static boolean historyRecord(Map<String,Object> record,Map<String,List<String>> facets) {
        String text=(String)record.get("text");
        return "teaching_history_statement".equals(record.get("role")) &&
                topicRelated(text,facets) && !RequirementCoverage.otherTopic(text,facets.keySet()) &&
                !text.matches("(?s).*(?:团队|机构累计|公司累计|旁听|参训|教材编写|计划|筹备).*" );
    }
    private boolean structuredJoint(Map<String,Object> record,Map<String,List<String>> facets) {
        if(facets.values().stream().anyMatch(terms->!TeachingEvents.supportsTopic(record,terms)))return false;
        if((globalTeachingHistory||!minimums.isEmpty())&&!"teaching_history_statement".equals(record.get("role")))return false;
        for(Map.Entry<String,List<String>> feature:features.entrySet())if(!TeachingEvents.supportsFeature(record,feature.getKey(),feature.getValue()))return false;
        for(Map<String,Object> bound:minimums)if(!TeachingEvents.supportsBound(record,(String)bound.get("unit"),(Integer)bound.get("minimum"),"each".equals(bound.get("scope"))))return false;
        for(List<String> terms:eachFeatures.values())if(!RequirementCoverage.contains(TeachingEvents.field(record,"each_activity"),terms))return false;
        return true;
    }
    private static List<Map<String,List<String>>> atomicScopes(Map<String,List<String>> facets) {
        if(facets.size()<2) return List.of(facets);
        List<Map<String,List<String>>> scopes=new ArrayList<>();
        for(Map.Entry<String,List<String>> facet:facets.entrySet()) scopes.add(Map.of(facet.getKey(),facet.getValue()));
        return scopes;
    }
    private static String scopeLabel(Map<String,List<String>> scope,Map<String,List<String>> all) {
        return all.size()>1?"："+String.join("、",scope.keySet()):"";
    }
    private boolean eachFeature(String text,Map<String,List<String>> facets,List<String> terms) {
        for(String clause:text.split("[\\r\\n，,。；;]+")) {
            Matcher reports=Pattern.compile("("+NUM+")份活动记录(?:均|都)").matcher(clause);
            int requiredCount=minimums.stream().filter(b->List.of("场","次","期").contains(b.get("unit")))
                    .mapToInt(b->(Integer)b.get("minimum")).max().orElse(Integer.MAX_VALUE);
            boolean allReports=reports.find() && number(reports.group(1))>=requiredCount;
            if((allReports || clause.matches("(?s).*(?:每场|每次|每期|各场|各次|各期|"+NUM+"(?:场|次|期)(?:均|都)).*")) &&
                    !clause.matches("(?s).*(?:仅|只|部分|其中一|任一|一场|一次|一期|总计|累计).*") &&
                    RequirementCoverage.contains(clause,terms) && featureScoped(text,facets,List.of(clause))) return true;
        }
        return false;
    }
    private static boolean eachBound(String text,String unit,int minimum,Map<String,List<String>> facets) {
        for(String clause:text.split("[\\r\\n，,。；;]+")) {
            if(!clause.matches("(?s).*(?:每场|每次|每期|各场|各次|各期).*")) continue;
            if(clause.matches("(?s).*(?:总计|累计|共计|仅一场|其中一场).*")) continue;
            if(hasCount(clause,unit,minimum,true) && featureScoped(text,facets,List.of(clause)) &&
                    !RequirementCoverage.otherTopic(text,facets.keySet())) return true;
        }
        return false;
    }
    private boolean jointEventSet(String text,Map<String,List<String>> facets) {
        if(facets.values().stream().anyMatch(terms->!RequirementCoverage.contains(text,terms))) return false;
        for(Map<String,Object> bound:minimums) {
            String unit=(String)bound.get("unit");int minimum=(Integer)bound.get("minimum");
            if("each".equals(bound.get("scope"))) { if(!eachBound(text,unit,minimum,facets)) return false; }
            else if(!topicCount(text,unit,minimum,facets)) return false;
        }
        for(Map.Entry<String,List<String>> feature:features.entrySet())
            if(!(InstructorMode.applies(feature.getKey())?InstructorMode.prose(text,feature.getKey(),facets):featureScoped(text,facets,feature.getValue()))) return false;
        for(List<String> terms:eachFeatures.values()) if(!eachFeature(text,facets,terms)) return false;
        return !text.matches("(?s).*(?:一场|一次|一期)(?:为|是|采用|面向).*" );
    }
    static Map<String,Object> check(String label,String proof) {
        return Map.of("criterion",label,"status",proof.isEmpty()?"needs_evidence":"source_supported",
                "evidence",proof.substring(0,Math.min(220,proof.length())),"verified",false);
    }
    private static Map<String,Object> sourcedCheck(String label,String proof,List<Map<String,Object>> records) {
        Map<String,Object> result=new LinkedHashMap<>(check(label,proof));
        List<Map<String,Object>> matches=records.stream().filter(r->TeachingEvents.structured(r)&&Objects.equals(proof,r.get("text"))).toList();
        if(!proof.isEmpty()&&matches.size()==1)result.put("source_event",TeachingEvents.metadata(matches.get(0)));
        return result;
    }
}
