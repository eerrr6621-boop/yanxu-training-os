package com.training;

import java.util.*;
import java.util.regex.*;

/** Small auditable course vocabulary, used alongside semantic retrieval, not as
 * a credential registry. Unknown wording is allowed into semantic comparison. */
final class RequirementCoverage {
    private record TopicSpan(String text,int start,int end) {}
    private record TopicParse(List<TopicSpan> topics,List<TopicSpan> unresolved) {}
    // A teaching verb consumes its aspect marker before the course object.
    // Kept as one lexical rule for query and source object extraction.
    /** Grammar-only alternatives, never a learned/domain synonym dictionary. */
    private static final class TopicTerms extends AbstractList<String> {
        private final List<String> variants;
        TopicTerms(String raw) { variants=raw.equals(topicGrammar(raw))?List.of(raw):List.of(raw,topicGrammar(raw)); }
        public String get(int index) { return variants.get(index); }
        public int size() { return variants.size(); }
    }
    private static String topicGrammar(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\s《》]","").replaceAll("如何|怎么|怎样","");
    }
    private static final Map<String,List<String>> FACETS = new LinkedHashMap<>();
    static {
        add("服务与商务礼仪", "服务礼仪","商务礼仪","接待礼仪","职业形象","服务专员","厅堂接待");
        add("客户投诉处置", "客户投诉","投诉处理","投诉处置","客诉","争议化解","冲突化解","服务补救");
        add("亲子财商", "亲子财商","金小葵","少儿财商","财商启蒙","儿童财商","财商思维");
        add("金融防骗反诈", "防骗","反诈","诈骗","防诈","金融财产安全","转账骗局","电信骗局","金融安全");
        add("劳动用工风险", "劳动用工","用工风险","劳动风险","劳动纠纷","试用期解除","解除合同");
        add("员工与劳动关系", "劳动关系","员工关系");
        add("岗位招聘", "招聘","面试技巧","招聘配置");
        add("求职与职业发展", "求职","职业发展","职业规划","就业指导");
        add("简历制作", "简历制作","简历辅导","改简历");
        add("财税实务", "账务","涉税","税务","税收","纳税申报");
        add("成本与预算", "成本核算","成本怎么核算","费用预算","预算编制","成本管控");
        add("经营数据分析", "经营数据分析","经营分析","经营数据解读");
        add("员工情绪与压力", "情绪管理","职场焦虑","压力管理","压力调节","情绪调节","负面情绪","负面感受");
        add("银行业务合规", "银行合规","业务风险","风险合规","操作风险","银行业务风险","入职合规培训");
        add("办公数据处理", "excel","数据透视","办公效能","报表自动化","工作表函数");
        add("人工智能应用", "人工智能","生成式","提示词","ai办公","大语言模型");
        add("课程开发", "课程开发","教学目标","练习设计");
        add("领导力", "领导力","团队领导");
        add("战略管理", "战略管理","战略规划");
    }
    static void add(String name, String... terms) {
        List<String> all=new ArrayList<>();all.add(name);all.addAll(Arrays.asList(terms));FACETS.put(name,all);
    }
    static boolean contains(String text,List<String> terms) {
        if(terms instanceof TopicTerms) {
            String grammar=topicGrammar(text);
            return terms.stream().anyMatch(term->{
                String demand=topicGrammar(term);
                // One-way, narrow action entailment: an explicit X业务办理
                // supports X处理, never the reverse or an unrelated action.
                return grammar.contains(demand) || demand.endsWith("处理") && demand.length()>2 &&
                        grammar.contains(demand.substring(0,demand.length()-2)+"业务办理");
            });
        }
        String n = text.toLowerCase(Locale.ROOT).replaceAll("\\s+","");
        for(String term:terms) if(n.contains(term.toLowerCase(Locale.ROOT))) return true;
        return false;
    }
    static boolean otherTopic(String text,Set<String> requested) {
        for(Map.Entry<String,List<String>> facet:FACETS.entrySet())
            if(!requested.contains(facet.getKey()) && contains(text,facet.getValue())) return true;
        return false;
    }
    final LinkedHashMap<String,List<String>> required = new LinkedHashMap<>();
    private final Map<String,TopicSpan> genericTopics=new LinkedHashMap<>();
    private final List<TopicSpan> unresolvedTopics=new ArrayList<>();
    private final String originalQuery;
    final String query;
    final boolean locationHardReview;
    final boolean uncoveredConjunction;
    final boolean requiresTeachingHistory;
    final RequirementConstraints constraints;
    final RequirementTopic.Result topicContract;
    private final Map<String,Set<String>> courseScopes=new LinkedHashMap<>();
    RequirementCoverage(String source) {
        originalQuery=source;
        topicContract=RequirementTopic.parse(source);
        constraints=new RequirementConstraints(source,topicContract);
        query = constraints.positive;
        // Retrieval focus must not erase an independently supplied hard condition.
        String professional = RequirementNarrative.topicView(query.replaceFirst("^补充要求[:：]", ""));
        for(Map.Entry<String,List<String>> facet:FACETS.entrySet())
            if(contains(professional,facet.getValue())) required.put(facet.getKey(),facet.getValue());
        // Small, explicit compound aliases: wording maps to a topic, not to an
        // unverified qualification. Evidence must still support the canonical topic.
        if(professional.matches("(?s).*(?:迎接|迎送).*(?:引导|接待).*(?:送别|举止|规范).*"))
            required.put("服务与商务礼仪",FACETS.get("服务与商务礼仪"));
        if(professional.matches("(?s).*(?:表格|台账).*(?:汇总|统计).*(?:图表|统计图).*"))
            required.put("办公数据处理",FACETS.get("办公数据处理"));
        if(professional.matches("(?s).*(?:内训师|讲师).*(?:目标).*(?:结构).*(?:练习).*"))
            required.put("课程开发",FACETS.get("课程开发"));
        // Labor-risk teaching is the specific requirement; a second broad HR
        // label must not accidentally become an unrelated extra certificate.
        if(required.containsKey("劳动用工风险")) required.remove("员工与劳动关系");
        Matcher titles=Pattern.compile("《([^》]{2,80})》").matcher(professional);
        while(titles.find()) required.put("指定课程："+titles.group(1),List.of(titles.group(1)));
        // Unknown fields/course objects are independent, source-backed content
        // requirements, not a similarity threshold or a new industry word list.
        // The shared parser exclusively owns carried topic/history syntax.
        // The legacy extractor may inspect residual grammar, never re-parse a
        // consumed reference as another topic such as "这门课的经历".
        StringBuilder residual=new StringBuilder(source);
        for(RequirementTopic.Consumption carried:topicContract.consumedSpans())
            for(int at=carried.span().start();at<carried.span().end();at++)residual.setCharAt(at,' ');
        TopicParse legacy=topicObjects(residual.toString(),true);
        List<TopicSpan> anchors=new ArrayList<>(legacy.topics());
        for(RequirementTopic.Course course:topicContract.courses()) {
            RequirementTopic.Span anchor=course.anchorSpans().get(0);
            anchors.add(new TopicSpan(course.canonicalAnchor(),anchor.start(),anchor.end()));
        }
        TopicParse parsed=new TopicParse(anchors,legacy.unresolved());
        unresolvedTopics.addAll(parsed.unresolved());
        // Masking owned anchors must not remove the preceding-topic context
        // that made an unknown mandatory follow-up pending in the old parser.
        if(!topicContract.courses().isEmpty())for(RequirementTopic.Unparsed residualClause:topicContract.unparsedClauses()) {
            String clause=residualClause.span().text().strip();
            // Retaining an exclusive clause in the provenance ledger is not
            // enough: without a typed binding it must also prevent admission.
            // A recognised topic elsewhere must not hide its unknown scope.
            if(clause.matches("(?s).*(?:(?:只|仅)(?:要|接受|限|用于)|唯一|只能|仅能|限于).*")
                &&!RequirementConstraints.knownExclusiveDelivery(clause)) {
                unresolvedTopics.add(new TopicSpan(clause,residualClause.span().start(),residualClause.span().end()));
                continue;
            }
            if(constraints.audienceIntent.isFutureRecipientClause(clause)||!AudienceIntent.mandatoryClause(clause))continue;
            RequirementConstraints routed=new RequirementConstraints(clause);
            if(routed.features.isEmpty()&&routed.minimums.isEmpty()&&!routed.teachingHistory&&!routed.singleInstructor&&!routed.qualificationRequired&&!routed.unknownExclusion)
                unresolvedTopics.add(new TopicSpan(clause,residualClause.span().start(),residualClause.span().end()));
        }
        for(TopicSpan topic:parsed.topics()) {
            boolean existingExact=required.values().stream().flatMap(Collection::stream)
                    .anyMatch(term->topicGrammar(term).equals(topicGrammar(topic.text())));
            if(existingExact) continue;
            String key="培训主题："+topic.text();
            required.put(key,new TopicTerms(topic.text()));genericTopics.put(key,topic);
        }
        if(required.isEmpty())for(RequirementTopic.Consumption carried:topicContract.consumedSpans())
            if(carried.kind().equals("training_stage"))
                unresolvedTopics.add(new TopicSpan(carried.span().text(),carried.span().start(),carried.span().end()));
        locationHardReview = source.matches("(?s).*(?:常驻|同城|本地).*") &&
                source.matches("(?s).*(?:必须|不接受|仅限|只接受|不得).*");
        for(RequirementTopic.Course course:topicContract.courses()) {
            Set<String> keys=new LinkedHashSet<>();
            for(Map.Entry<String,List<String>> entry:required.entrySet())
                if(entry.getValue().stream().anyMatch(term->topicGrammar(term).equals(topicGrammar(course.canonicalAnchor()))))keys.add(entry.getKey());
            courseScopes.put(course.id(),keys);
        }
        requiresTeachingHistory=constraints.teachingHistory||topicContract.courses().stream().anyMatch(RequirementTopic.Course::requiresHistory);
        boolean unknown=false;
        String residualPositive=new RequirementConstraints(residual.toString()).positive;
        for(String part:residualPositive.split("(?:以及|同时|和|及|与|、|[\\r\\n；;])")) {
            part=part.strip();
            final String module=part;
            if(part.length()<=36 && !part.matches("^(?:这场|本次|我们|请推荐|只能|不能|必须|希望老师|需要老师).*" ) &&
                    !part.matches("^(?:需要|要求|需|应)(?:面向|针对|面对面|线下|远程|线上|使用|采用).*" ) &&
                    !RequirementConstraints.completedTeachingRequirement(part) &&
                    part.matches("(?s).*.{2}(?:教学|课程|培训|实操|训练|编程|调试)$") &&
                    !required.isEmpty() && required.values().stream().noneMatch(terms->contains(module,terms)))
                unknown=true;
        }
        uncoveredConjunction=unknown || !unresolvedTopics.isEmpty();
    }
    private static TopicParse topicObjects(String source,boolean requirement) {
        List<TopicSpan> topics=new ArrayList<>(),unresolved=new ArrayList<>();
        Matcher clauses=Pattern.compile("[^\\r\\n，,。；;]+").matcher(source);
        boolean previousTopic=false;
        List<TopicSpan> referents=List.of();
        while(clauses.find()) {
            String clause=clauses.group();int clauseStart=clauses.start();
            if(requirement && (RequirementConstraints.knownRoleExclusion(clause) || RequirementConstraints.knownSingleInstructor(clause))) {
                previousTopic=false;continue;
            }
            Matcher body=Pattern.compile("(?:培训主题|培训内容|课程主题|课程内容|主讲课程|精品课程|专业领域|授课专长|课程|主题)(?:(?:都)?(?:为|是)|[:：])\\s*(.+)").matcher(clause);
            boolean found=body.find();
            if(!found && requirement) {
                body=Pattern.compile("^\\s*(?:培训主题|课程主题|培训内容)?\\s*《([^》]{2,80})》\\s*$").matcher(clause);
                found=body.find();
            }
            if(!found) {
                body=Pattern.compile("(?:"+RequirementConstraints.TEACHING_OBJECT_VERB+"|同时覆盖|必须覆盖)\\s*(.+)").matcher(clause);
                found=body.find();
            }
            if(!found && !requirement && !clause.matches("(?s).*(?:同事|他人|别人|其他(?:老师|讲师)|(?:公司|单位|机构|团队))(?:[^，,。；;]{0,12})(?:精通|擅长).*" ) &&
                    !clause.matches("(?s).*(?:精通|擅长).*(?:是|为)(?:同事|他人|别人|(?:本|所在)?(?:公司|单位|机构|团队)).*")) {
                body=Pattern.compile("(?:精通|擅长)\\s*(.+)").matcher(clause);
                found=body.find();
            }
            if(!found && requirement && !new RequirementConstraints(clause).teachingHistory) {
                body=Pattern.compile("^\\s*(?:(?:客户)?(?:需要|要求)(?:面向|针对)[^的]{1,30}的)?([^:：]{2,80}?)(?:的)?课程(?:的(?:老师|讲师))?\\s*$").matcher(clause);
                found=body.find();
            }
            if(!found && requirement && previousTopic) {
                body=Pattern.compile("^\\s*(?:以及|同时|和|与|及)\\s*(.+)").matcher(clause);
                found=body.find();
            }
            if(!found) {
                if(requirement && previousTopic && AudienceIntent.mandatoryClause(clause)) {
                    RequirementConstraints hard=new RequirementConstraints(clause);
                    if(hard.features.isEmpty() && hard.minimums.isEmpty() && !hard.teachingHistory && !hard.singleInstructor && !hard.qualificationRequired)
                        unresolved.add(new TopicSpan(clause.strip(),clauseStart,clauses.end()));
                }
                previousTopic=false;continue;
            }
            String raw=body.group(1);int start=clauseStart+body.start(1);
            if(requirement && RequirementTopic.containsReference(raw)) {
                unresolved.add(new TopicSpan(raw,start,start+raw.length()));previousTopic=false;continue;
            }
            if(requirement && raw.strip().matches("(?:这一主题|这个主题|该主题|这一课程|这门课程|该课程)(?:的)?(?:(?:课堂|教学|授课|课程)(?:记录|经历))?")) {
                if(referents.size()!=1) unresolved.add(new TopicSpan(raw,start,start+raw.length()));
                // The sole explicit antecedent remains the content requirement;
                // a completed predicate separately enforces its own history.
                previousTopic=false;continue;
            }
            if(requirement && Set.of("案例","授课记录","教学记录","授课经历").contains(raw.strip())) {
                RequirementConstraints hard=new RequirementConstraints(clause);
                if(!hard.features.isEmpty() || hard.teachingHistory) { previousTopic=false;continue; }
            }
            // Preserve unfamiliar exclusions, quantifiers and references as
            // unresolved. Never turn 不教X只讲Y into a positive X requirement.
            if(clause.matches("(?s).*(?:不教|未教|不讲|未讲|不培训|不是|而非|不要|不需要|无需|不能|仅|只讲|只教|计划|拟开设).*" ) ||
                    raw.matches("(?s).*(?:必须|至少|不少于|不低于|每场|每次|每门|已完成|已经完成|完成过|还需|且需|并要求|上述|这些|这一主题|这个主题|该主题|这一课程|这门课程|该课程|全部内容|所有模块|相关内容|相应课程).*")) {
                if(requirement) unresolved.add(new TopicSpan(raw,start,start+raw.length()));
                previousTopic=false;continue;
            }
            int left=0,right=raw.length();
            while(left<right && Character.isWhitespace(raw.charAt(left))) left++;
            while(right>left && Character.isWhitespace(raw.charAt(right-1))) right--;
            String trimmed=raw.substring(left,right);
            if(requirement) {
                Matcher historySuffix=Pattern.compile("(?:的)?(?:课堂|教学|授课|课程)(?:记录|经历)$").matcher(trimmed);
                if(historySuffix.find()) {
                    right=left+historySuffix.start();trimmed=raw.substring(left,right);
                }
            }
            Matcher suffix=Pattern.compile("(?:的)?(?:课程|工作坊|培训)(?:的(?:老师|讲师))?$|的(?:老师|讲师)$").matcher(trimmed);
            if(suffix.find()) right=left+suffix.start();
            String content=raw.substring(left,right);start+=left;
            Matcher separators=Pattern.compile("以及|并且|同时|和|与|及|、").matcher(content);
            List<int[]> parts=new ArrayList<>();int partStart=0;
            while(separators.find()) { parts.add(new int[]{partStart,separators.start()});partStart=separators.end(); }
            parts.add(new int[]{partStart,content.length()});
            // Explicit AND boundaries, not a bag of all words anywhere in the
            // resume. An unparseable/empty conjunct keeps the whole request open.
            boolean valid=true,added=false;List<TopicSpan> currentTopics=new ArrayList<>();
            for(int[] part:parts) {
                String piece=content.substring(part[0],part[1]);
                String item=piece.strip();int itemStart=start+part[0]+piece.indexOf(item);
                if(item.length()<2 || item.length()>80 || item.matches("(?s).*(?:本人|老师|讲师|必须|需要|要求|希望|完成教学|授课记录|授课经历|[<>≤≥]).*" ) ||
                        item.matches("课程|培训|教学|内容|模块|知识|技能|全部|所有|上述|其他")) valid=false;
                else { TopicSpan span=new TopicSpan(item,itemStart,itemStart+item.length());topics.add(span);currentTopics.add(span);added=true; }
            }
            if(!valid || !added) {
                if(requirement) unresolved.add(new TopicSpan(raw,clauseStart+body.start(1),clauseStart+body.end(1)));
            }
            previousTopic=valid&&added;
            if(previousTopic) referents=List.copyOf(currentTopics);
            else referents=List.of();
        }
        return new TopicParse(topics,unresolved);
    }
    private boolean genericSourceSupports(String text,String key) {
        if(!genericTopics.containsKey(key)) return true;
        List<String> terms=required.get(key);
        for(TopicSpan taught:topicObjects(text,false).topics())
            if(contains(taught.text(),terms)) return true;
        return false;
    }
    private boolean genericDenialConflict(String source) {
        boolean denied=false;
        for(String line:ProfessionalEvidence.normalized(source).split("\\R")) {
            line=line.strip();
            if(EvidenceSections.boundary(line)) {
                denied=line.matches("(?:未授课课程|未讲授课程|尚未开设课程|仅参训课程)[:：]?");continue;
            }
            if(denied) for(String key:genericTopics.keySet()) if(contains(line,required.get(key))) return true;
        }
        return false;
    }
    private Map<String,Object> querySpan(TopicSpan span) {
        return Map.of("text",originalQuery.substring(span.start(),span.end()),
                "source_start",originalQuery.codePointCount(0,span.start()),"source_end",originalQuery.codePointCount(0,span.end()),
                "offset_unit","unicode_code_point","source","requirement_original");
    }
    private boolean supportsCourse(Map<String,Object> record,RequirementTopic.Course course) {
        List<String> terms=new TopicTerms(course.canonicalAnchor());
        if(TeachingEvents.structured(record))return TeachingEvents.supportsTopic(record,terms);
        // Preserve the established facet contract for its exact canonical or
        // declared synonym anchor. Novel courses still need an explicit object.
        for(Map.Entry<String,List<String>> facet:FACETS.entrySet())
            if(facet.getValue().stream().anyMatch(term->topicGrammar(term).equals(topicGrammar(course.canonicalAnchor()))))
                return contains(Objects.toString(record.get("text")),facet.getValue());
        return topicObjects(Objects.toString(record.get("text")),false).topics().stream().anyMatch(t->contains(t.text(),terms));
    }
    private boolean historyConditions(Map<String,Object> record,RequirementTopic.Course course) {
        if(!course.requiresHistory())return true;
        if(!"teaching_history_statement".equals(record.get("role")))return false;
        String text=Objects.toString(record.get("text"));
        for(RequirementTopic.HistoryCondition condition:course.historyConditions()) {
            for(RequirementTopic.Span mode:condition.modeBindings()) {
                String label=HistoricalTeachingRequest.modeLabel(mode.text());List<String> terms=HistoricalTeachingRequest.modeTerms(mode.text());
                if(TeachingEvents.structured(record)) {
                    if(!TeachingEvents.supportsFeature(record,label,terms))return false;
                } else if(!InstructorMode.prose(text,label,Map.of(course.canonicalAnchor(),new TopicTerms(course.canonicalAnchor()))))return false;
            }
            if(TeachingEvents.structured(record)) {
                Map<?,?> event=TeachingEvents.metadata(record);
                if(!Boolean.TRUE.equals(event.get("completed_personal_teaching")))return false;
                if(condition.completed()&&"past_teaching".equals(event.get("completion_scope")))return false;
                if(condition.independent()&&!Boolean.TRUE.equals(event.get("independent_lead")))return false;
                for(RequirementTopic.Span audience:condition.audienceBindings())
                    if(!TeachingEvents.supportsFeature(record,"授课对象："+audience.text(),RequirementConstraints.audienceTerms(audience.text())))return false;
            } else {
                // The legacy record has already passed full role/source guards.
                // A legacy paragraph cannot upgrade a parsed past lecture to a
                // whole-course completion. If another complete event exists,
                // all its history conditions must hold within that same event.
                if(condition.completed()) {
                    List<TeachingEvents.Event> localEvents=TeachingEvents.extract(text).stream()
                        .filter(e->supportsCourse(e.record(),course)).toList();
                    if(localEvents.stream().anyMatch(e->e.completionScope.equals("past_teaching"))&&
                            localEvents.stream().noneMatch(e->e.completionScope.equals("full_course")&&historyConditions(e.record(),course)))return false;
                }
                // Additional explicit modifiers must be in a teaching predicate,
                // never a coincidental word in a course title or later example.
                if(condition.personal()&&!ProfessionalEvidence.ownTeaching(text))return false;
                if(condition.independent()&&!text.matches("(?s)^(?:20\\d{2}年[^，,。；;]{0,20})?(?:本人|我|该讲师|该教师)?(?:(?:已(?:经)?|实际|曾(?:经)?|亲自)){0,5}独立(?:主讲|讲授|授课|讲完|完成[^，,。；;]{2,80}(?:教学|授课)).*"))return false;
                for(RequirementTopic.Span audience:condition.audienceBindings())
                    if(!RequirementConstraints.featureScoped(text,Map.of(course.canonicalAnchor(),new TopicTerms(course.canonicalAnchor())),RequirementConstraints.audienceTerms(audience.text())))return false;
            }
        }
        return true;
    }
    private boolean courseCondition(Map<String,Object> record,Map<String,List<String>> scope) {
        for(RequirementTopic.Course course:topicContract.courses()) if(course.requiresHistory()&&
                !Collections.disjoint(courseScopes.getOrDefault(course.id(),Set.of()),scope.keySet())) {
            if(!supportsCourse(record,course)||!historyConditions(record,course))return false;
        }
        return true;
    }
    @SuppressWarnings("unchecked")
    void assess(Map<String,Object> candidate,String effectiveText,boolean semanticReady) {
        boolean denialConflict=EvidenceSections.denialConflicts(effectiveText,required) || genericDenialConflict(effectiveText);
        boolean scoped=candidate.get("semantic") instanceof Map<?,?> scopeDetails &&
            Boolean.FALSE.equals(scopeDetails.get("evidence_complete")) && EvidenceSections.verified(effectiveText,scopeDetails);
        boolean mixed=scoped&&EvidenceSections.MIXED_VERSION.equals(((Map<?,?>)candidate.get("semantic")).get("scoring_scope"));
        if(scoped&&!mixed) effectiveText=EvidenceSections.maskedSource(effectiveText,(Map<?,?>)candidate.get("semantic"));
        EvidenceSections.MixedEvidence original=mixed?EvidenceSections.mixedEvidence(effectiveText,(Map<?,?>)candidate.get("semantic")):null;
        // Qualifications are not teaching events. Keep safe records from the
        // same already-masked scope for display only, before event filtering.
        List<Map<String,Object>> qualificationRecords=mixed?original.records():ProfessionalEvidence.records(effectiveText);
        List<String> units=mixed?original.units():ProfessionalEvidence.units(effectiveText);
        List<TeachingEvents.Event> events=mixed?original.events():TeachingEvents.extract(effectiveText).stream().filter(TeachingEvents.Event::explicitBlock).toList();
        List<Map<String,Object>> records=mixed?new ArrayList<>(original.records()):new ArrayList<>(qualificationRecords.stream().filter(record->{
            int at=((Number)record.get("source_offset")).intValue();
            // Narrative extraction is additive: even a usable partial event may
            // not carry every legacy mode/time fact. Explicit field blocks keep
            // their strict anti-detachment boundary; source role guards still
            // run before either path yields an admissible record.
            return events.stream().noneMatch(event->at>=event.start&&at<event.end&&!event.form.equals("narrative"));
        }).toList());
        if(!mixed)for(TeachingEvents.Event event:events)if(event.personalLead())records.add(event.record());
        if(mixed)candidate.put("scoped_evidence_role_limits",records.stream().filter(r->r.containsKey("scope_evidence_limit")).map(r->Map.of(
            "evidence_id",r.get("evidence_id"),"source_line",r.get("source_line"),"source_offset",r.get("source_offset"),
            "offset_unit","java_utf16_code_unit","source","normalized_resume","source_record_role",r.get("source_record_role"),
            "role",r.get("role"),"scope_evidence_limit",r.get("scope_evidence_limit"))).toList());
        candidate.put("teaching_events",events.stream().map(TeachingEvents.Event::map).toList());
        // This constructor receives matching text (sometimes guided canonical
        // or legacy-composed text), not necessarily the user's raw field value.
        candidate.put("requirement_topic_contract",new RequirementTopic.Result(topicContract.source(),"requirement_matching_text",
                topicContract.courses(),topicContract.consumedSpans(),topicContract.unparsedClauses(),topicContract.inputProvenance()).toMap());
        List<Map<String,Object>> coverage=new ArrayList<>();
        int covered=0;
        for(Map.Entry<String,List<String>> facet:required.entrySet()) {
            String evidence="",evidenceId="",role=""; int sourceLine=0;
            for(Map<String,Object> record:records) {
                String unit=(String)record.get("text");
                boolean topic=TeachingEvents.structured(record)?TeachingEvents.supportsTopic(record,facet.getValue()):
                    contains(unit,facet.getValue())&&genericSourceSupports(unit,facet.getKey());
                if(topic && (!constraints.globalTeachingHistory || "teaching_history_statement".equals(record.get("role"))) &&
                    courseCondition(record,Map.of(facet.getKey(),facet.getValue()))&&
                    (ProfessionalEvidence.grade(unit)>0 || ProfessionalEvidence.isTeachingRecord(unit,effectiveText) ||
                    TeachingEvents.structured(record) ||
                    "岗位招聘".equals(facet.getKey()) && unit.matches("(?s).*(?:负责|从事|招聘经验|招聘配置).*"))) {
                    evidence=unit.substring(0,Math.min(220,unit.length()));
                    evidenceId=(String)record.get("evidence_id");sourceLine=(Integer)record.get("source_line");role=(String)record.get("role");break;
                }
            }
            boolean supported=!evidence.isEmpty();
            if(supported) covered++;
            Map<String,Object> item=new LinkedHashMap<>(Map.of("criterion",facet.getKey(),"status",supported?"source_supported":"needs_evidence",
                    "evidence",evidence,"evidence_id",evidenceId,"source_line",sourceLine,"role",role,"verified",false));
            if(genericTopics.containsKey(facet.getKey())) item.put("requirement_source_span",querySpan(genericTopics.get(facet.getKey())));
            coverage.add(item);
        }
        Double similarity=LocalSemantic.similarity(candidate);
        boolean full=!required.isEmpty() && covered==required.size();
        boolean ruleRelevant=Boolean.TRUE.equals(candidate.get("_rule_relevant"));
        boolean semanticRecall=semanticReady && similarity!=null && ruleRelevant;
        // An exact, affirmative source hit survives either side of any model
        // threshold. Semantic-only hits remain review candidates, not credentials.
        boolean literal=literalEvidence(query,events.isEmpty()?units:records.stream().map(r->(String)r.get("text")).toList());
        boolean history=!constraints.globalTeachingHistory || records.stream().anyMatch(r->
                "teaching_history_statement".equals(r.get("role")) && literalEvidence(query,List.of((String)r.get("text"))));
        boolean contentSupported=full || (required.isEmpty() && literal && history);
        // A mere incidental mention cannot provide the audience/duration of a
        // new course. Hard checks see only records with a real content anchor.
        List<Map<String,Object>> boundRecords=genericTopics.isEmpty()?records:records.stream().filter(record->
                genericTopics.keySet().stream().anyMatch(key->TeachingEvents.structured(record)?TeachingEvents.supportsTopic(record,required.get(key)):
                    genericSourceSupports((String)record.get("text"),key))).toList();
        List<Map<String,Object>> constraintChecks=constraints.assess(boundRecords,required,
                (!genericTopics.isEmpty()||topicContract.courses().size()>1)&&required.size()>1,this::courseCondition,qualificationRecords);
        List<Map<String,Object>> audienceContext=constraints.audienceContext(boundRecords,required,courseScopes);
        candidate.put("audience_context",audienceContext);
        for(RequirementTopic.Course course:topicContract.courses()) if(course.requiresHistory()) {
            Map<String,Object> proof=records.stream().filter(record->supportsCourse(record,course)&&historyConditions(record,course)).findFirst().orElse(null);
            Map<String,Object> check=new LinkedHashMap<>(RequirementConstraints.check("课程授课经历："+course.canonicalAnchor(),proof==null?"":Objects.toString(proof.get("text"))));
            check.put("course_id",course.id());check.put("history_conditions",course.historyConditions().stream().map(h->h.toMap(originalQuery,"requirement_matching_text")).toList());
            check.put("requirement_source_span",course.historyConditions().get(0).span().toMap(originalQuery,"requirement_matching_text"));
            if(proof!=null) {
                check.put("evidence_id",proof.get("evidence_id"));check.put("source_line",proof.get("source_line"));
                if(TeachingEvents.structured(proof))check.put("source_event",TeachingEvents.metadata(proof));
            }
            constraintChecks.add(check);
        }
        if(constraints.singleInstructor) constraintChecks.add(RequirementConstraints.check(
                "同一候选人的全部独立课程模块均有资料支持（不合并不同讲师）",
                full&&!uncoveredConjunction?"本候选人独立资料覆盖 "+required.size()+" 项课程内容":""));
        if(denialConflict) constraintChecks.add(Map.of("criterion","同主题存在未授课或仅参训声明，需先核对冲突","status","needs_evidence","evidence","","verified",false));
        if(candidate.get("semantic") instanceof Map<?,?> semanticDetails && !constraints.handlesWorkerExclusions(semanticDetails))
            constraintChecks.add(Map.of("criterion","尚未逐项核实的排除条件","status","needs_evidence","evidence","","verified",false));
        boolean hardSupported=constraintChecks.stream().allMatch(c->"source_supported".equals(c.get("status")));
        boolean evidenceReview=candidate.get("semantic") instanceof Map<?,?> details && Boolean.FALSE.equals(details.get("evidence_complete")) && !scoped;
        boolean admitted=contentSupported && !locationHardReview && !uncoveredConjunction && hardSupported && !evidenceReview;
        coverage.addAll(constraintChecks);
        candidate.put("unparsed_requirements",unresolvedTopics.stream().map(this::querySpan).toList());
        candidate.put("requirement_coverage",coverage);
        candidate.put("coverage_complete",(full || required.isEmpty() && literal && history) && !uncoveredConjunction && hardSupported && !evidenceReview);
        candidate.put("match_tier",admitted?"source_supported":"needs_review");
        candidate.put("_admitted",admitted);
        candidate.put("_review_candidate",covered>0 || semanticRecall || literal || (locationHardReview && ruleRelevant));
        // Sort on coverage first. Professional tags remain visible but no longer
        // turn a broad overlapping domain into proof of all requested topics.
        double ratio=required.isEmpty()?0:covered/(double)required.size();
        candidate.put("content_rank",full ? 200+ratio : required.isEmpty() && semanticRecall ? 100+similarity : ratio);
        List<String> gaps=(List<String>)candidate.get("gaps");
        for(Map<String,Object> context:audienceContext)gaps.add("对象适配待确认："+context.get("requested_audience")+
            (Boolean.TRUE.equals(context.get("source_audience_experience_stated"))?"；原文相关对象说明仅供核对，不等于适配已确认":
                "；简历尚未明确同课程同对象授课经历（非硬性门槛，不代表无法适配）"));
        for(Map<String,Object> check:constraintChecks) if(!"source_supported".equals(check.get("status"))) gaps.add("待核对："+check.get("criterion")+"；相关性得分不能代替此项证据");
        if(evidenceReview) gaps.add("简历中有尚未完整解析的证据上下文，本次不作自动确定人选");
        if(scoped) gaps.add("仅按已独立核对的课程章节匹配；其他未完整解析的章节未计入评分或授课证明");
        if(!full && !required.isEmpty()) gaps.add("需求包含 "+required.size()+" 项内容，当前原文支持 "+covered+" 项；缺项不能由综合相似度补齐");
        if(locationHardReview) gaps.add("需求含硬性常驻地点限制，档案省市不等于已确认的实际出发地；需人工确认后再确定候选");
        if(uncoveredConjunction) gaps.add("需求中有尚未可靠识别或关联的课程与限制条件，请核对完整要求后再确定人选");
        if(requiresTeachingHistory && !contentSupported) gaps.add("需求要求实际授课经历；课程介绍、参训、助教或筹备不能代替对应授课记录");
        if(semanticRecall && !full) gaps.add("语义检索发现相关表述，请核对课程内容与授课经历，不能据此认定资历");
        candidate.put("retrieval_method",full?"evidence_coverage":literal?"literal_evidence":semanticRecall?"semantic_review":"review");
        Long modelScore=semanticReady && similarity!=null?Math.round(100*Math.max(0,Math.min(1,similarity))):null;
        candidate.put("recommendation_score",modelScore);candidate.put("model_score",modelScore);
        candidate.put("score",modelScore);candidate.put("match_score",modelScore);
        // Internal fallback ordering is explicitly not shown as a model score.
        candidate.put("ranking_score",modelScore!=null?modelScore:((Number)candidate.get("professional_score")).doubleValue());
        candidate.put("score_source",modelScore==null?"unscored_evidence_fallback":"local_quantized_model");
        candidate.put("recommendation_score_rule","模型匹配分 = 相似度×100后四舍五入（限制在0—100）；就近分单独用于划定范围，不相加。模型未评分时显示未评分，仅按文字依据提供参考顺序。模型分不是授课能力认证或胜任概率。");
        candidate.put("recommendation_score_parts",modelScore==null?Map.of():Map.of("量化模型匹配",modelScore));
    }
    static boolean literalEvidence(String query,List<String> units) {
        for(String raw:query.split("[\\r\\n，,。；;]+")) {
            String needle=raw.replaceFirst("^(?:补充要求)[:：]","")
                    .replaceFirst("^(?:培训主题|培训内容|师资要求|讲师要求)[:：]","")
                    .replaceFirst("^(?:希望|需要|计划|想要|请)?(?:开展|开设|安排|学习|培训)?","")
                    .replaceFirst("^(?:给|为)","").strip();
            if(needle.matches(".*(?:预算|课时|日期|开课|地点|费用|老师|讲师|必须|不能).*")) continue;
            String[] pieces=needle.split("(?:做过|提供过|曾提供|曾开展)");
            if(needle.length()<4 || needle.length()>100) continue;
            for(String unit:units) {
                String hay=unit.replaceAll("[\\s《》]","").toLowerCase(Locale.ROOT);
                boolean all=true;
                for(String p:pieces) {
                    p=p.replaceAll("[\\s《》]","").toLowerCase(Locale.ROOT);
                    if(p.length()<4 || !hay.contains(p)) { all=false;break; }
                }
                if(all) return true;
            }
        }
        return false;
    }
}
