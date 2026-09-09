package com.training;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Versioned guided input, not a natural-language fact or qualification parser.
 * Canonical matching text and original field provenance are kept distinct. */
final class RequirementInput {
    static final String VERSION="guided_requirement_v1";
    static final List<String> FIELDS=List.of("unit","topic","audience","goals","date","hours","preference","extra");
    private static final List<String> ORIGINAL_LABELS=List.of("客户单位","培训主题","参训对象","希望解决的问题","期望日期","预计课时","师资要求","补充说明");
    private static final List<String> CANONICAL_LABELS=List.of("客户单位","培训主题","培训对象","培训目标","授课日期","预计课时","师资要求","补充说明");
    private static final List<Integer> LIMITS=List.of(200,200,200,1600,80,32,1200,1200);
    // ECMAScript String.trim whitespace: used by the existing frontend renderer.
    private static final String JS_SPACE="[\\x09-\\x0D\\x20\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]";
    // An unresolved explicit instruction stays visible. These are structural
    // actor/action signals, not topic vocabulary or a claim of full NLP parsing.
    private static final Pattern EXPLICIT_CONDITION=Pattern.compile("必须|不得|不能|不接受|仅限|只接受|至少|至多|不超过|不少于|不低于|每场|每次|每门|要求|需要|需让|须让|并让|且让|应当|"+
            "(?:本人|讲师|老师|教师)[^。；;]{0,30}(?:主讲|讲授|讲完|授课|完成)|"+
            "(?:让|带领|组织|指导|安排)(?:所有|每位|每个|各位)?(?:学员|学生|参训人员)|"+
            "(?:学员|学生|参训人员)[^。；;]{0,20}(?:现场|当堂|课堂)");
    final boolean guided;
    final String originalText,canonicalText;
    private final LinkedHashMap<String,String> rawFields=new LinkedHashMap<>();
    private final LinkedHashMap<String,String> values=new LinkedHashMap<>();
    private final List<Map<String,Object>> ledger=new ArrayList<>();
    private final List<String> pending=new ArrayList<>();
    private final Long demandId;

    private RequirementInput(String original,String source,Long demandId) {
        guided=false;originalText=original;canonicalText=source;this.demandId=demandId;
    }
    private RequirementInput(Object contract,Object original,Long demandId) {
        guided=true;this.demandId=demandId;
        if(!(original instanceof String)) throw invalid("引导需求必须同时提供字符串原文");
        originalText=(String)original;
        if(originalText.length()>10000) throw invalid("客户需求不能超过10000个字符");
        if(!(contract instanceof Map<?,?> raw)) throw invalid("requirement_contract 必须是对象");
        if(!raw.keySet().equals(Set.of("schema_version","fields")) || !VERSION.equals(raw.get("schema_version")))
            throw invalid("不支持的需求字段契约版本或契约属性");
        if(!(raw.get("fields") instanceof Map<?,?> fields) || !fields.keySet().equals(new HashSet<>(FIELDS)))
            throw invalid("引导需求必须保留全部8个已知字段，不支持额外字段");
        StringBuilder rendered=new StringBuilder(),canonical=new StringBuilder();
        for(int i=0;i<FIELDS.size();i++) {
            String key=FIELDS.get(i);Object value=fields.get(key);
            if(!(value instanceof String)) throw invalid(ORIGINAL_LABELS.get(i)+"必须是字符串，未填写请保留空字符串");
            String rawValue=(String)value,trimmed=trim(rawValue);
            if(rawValue.length()>LIMITS.get(i)) throw invalid(ORIGINAL_LABELS.get(i)+"超过字段长度限制");
            if(trimmed.matches("(?s).*[\\p{Cntrl}\\u2028\\u2029].*")) throw invalid(ORIGINAL_LABELS.get(i)+"不支持字段内换行或控制字符");
            String normalized=Normalizer.normalize(trimmed,Normalizer.Form.NFKC);
            rawFields.put(key,rawValue);values.put(key,normalized);
            Map<String,Object> entry=new LinkedHashMap<>();
            entry.put("field",key);entry.put("field_path","requirement_contract.fields."+key);
            entry.put("label",ORIGINAL_LABELS.get(i));entry.put("raw_value",rawValue);entry.put("canonical_value",normalized);
            entry.put("normalization","NFKC after ECMAScript trim; canonical offsets are not raw-field offsets");
            String purpose=switch(key) {
                case "topic" -> "course_content";
                case "audience" -> "training_audience";
                case "date","hours" -> "requested_training_schedule";
                case "goals" -> "requested_training_goal_not_past_teaching";
                case "unit" -> "customer_context";
                default -> "free_text_requirement";
            };
            entry.put("purpose",purpose);
            boolean review=!trimmed.isEmpty() && (key.equals("preference") || key.equals("extra") ||
                    List.of("topic","unit","goals").contains(key) && EXPLICIT_CONDITION.matcher(normalized).find());
            if(review) pending.add(key);
            entry.put("status",trimmed.isEmpty()?"not_provided":review?"requires_review":"field_preserved");
            if(!trimmed.isEmpty()) {
                if(rendered.length()>0) rendered.append('\n');
                rendered.append(ORIGINAL_LABELS.get(i)).append('：');int originalStart=rendered.length();rendered.append(trimmed);
                entry.put("original_span",span(rendered.toString(),originalStart,rendered.length(),"guided_rendered_original"));
                if(canonical.length()>0) canonical.append('\n');
                canonical.append(CANONICAL_LABELS.get(i)).append('：');int canonicalStart=canonical.length();canonical.append(normalized);
                entry.put("canonical_span",span(canonical.toString(),canonicalStart,canonical.length(),"requirement_canonical"));
            }
            ledger.add(entry);
        }
        if(values.get("topic").isEmpty()) throw invalid("请填写培训主题");
        if(!rendered.toString().equals(originalText)) throw invalid("需求原文与本次引导字段不一致，请保留全部要求后重新提交");
        if(!values.get("date").isEmpty()) {
            if(!values.get("date").matches("20\\d{2}-\\d{2}-\\d{2}")) throw invalid("计划日期请使用YYYY-MM-DD格式");
            ScheduleDates.canonical(values.get("date"));
        }
        if(!values.get("hours").isEmpty()) {
            if(!values.get("hours").matches("\\d+(?:\\.\\d+)?")) throw invalid("预计课时应为大于0的数字");
            BigDecimal hours=new BigDecimal(values.get("hours"));
            if(hours.signum()<=0 || !Double.isFinite(hours.doubleValue())) throw invalid("预计课时应为有限的正数");
        }
        canonicalText=canonical.toString();
    }
    static RequirementInput from(Map<String,Object> body,String legacySource,Long demandId) {
        Object original=body.containsKey("requirement")?body.get("requirement"):body.get("requirement_text");
        if(!body.containsKey("requirement_contract"))
            return new RequirementInput(original instanceof String?(String)original:"",legacySource,demandId);
        if(body.containsKey("requirement") && body.containsKey("requirement_text") && !Objects.equals(body.get("requirement"),body.get("requirement_text")))
            throw invalid("两个需求原文字段不一致，不能忽略其中一个");
        return new RequirementInput(body.get("requirement_contract"),original,demandId);
    }
    String date() { return values.getOrDefault("date",""); }
    double hours() { String value=values.getOrDefault("hours","");return value.isEmpty()?0:new BigDecimal(value).doubleValue(); }
    String field(String key) { return values.getOrDefault(key,""); }
    Map<String,Object> toMap() {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("schema_version",guided?VERSION:"legacy_text_v1");result.put("mode",guided?"guided":"raw");
        result.put("original_text",originalText);result.put("matching_text",canonicalText);
        result.put("matching_text_source",guided?"requirement_canonical":"legacy_composed_requirement");
        result.put("field_ledger",ledger);result.put("fields",rawFields);result.put("requires_input_review",!pending.isEmpty());
        result.put("review_fields",new ArrayList<>(pending));result.put("source_demand_id",demandId);
        result.put("notice",guided?"本次字段是匹配依据，不写回已有需求；规范化文本与字段原文分别保留，培训目标不等于本人授课经历。":"沿用完整原文入口，复杂或尚未解析条件仍需核对。");
        return result;
    }
    /** Attach provenance after existing coverage; never make an unproved item pass. */
    void annotate(Map<String,Object> candidate) {
        annotate(candidate,null);
    }
    @SuppressWarnings("unchecked")
    void annotate(Map<String,Object> candidate,String effectiveSource) {
        if(!guided) return;
        List<Map<String,Object>> checks=new ArrayList<>();
        for(Object value:(List<?>)candidate.getOrDefault("requirement_coverage",List.of())) {
            Map<String,Object> item=new LinkedHashMap<>((Map<String,Object>)value);
            Map<String,Object> requirementSpan=null;
            if(item.get("requirement_source_span") instanceof Map<?,?> existing) {
                requirementSpan=new LinkedHashMap<>((Map<String,Object>)existing);requirementSpan.put("source","requirement_canonical");
            } else if(Objects.equals(item.get("criterion"),"授课对象："+field("audience")) && !field("audience").isEmpty())
                requirementSpan=new LinkedHashMap<>((Map<String,Object>)entry("audience").get("canonical_span"));
            if(requirementSpan!=null) {
                item.put("requirement_source_span",requirementSpan);
                List<Map<String,Object>> origins=origins(requirementSpan);
                item.put("requirement_field_sources",origins);
                if(origins.size()==1) item.put("requirement_field_source",origins.get(0));
            }
            item.put("requirement_text_source","requirement_canonical");checks.add(item);
        }
        List<String> unresolvedFields=new ArrayList<>();
        List<Map<String,Object>> fieldAssessments=new ArrayList<>();
        for(String key:pending) {
            Map<String,Object> resolved=completeFieldSupport(key,candidate,checks,effectiveSource);
            fieldAssessments.add(resolved);
            if(Boolean.TRUE.equals(resolved.get("duplicate_gate_discharged")))continue;
            unresolvedFields.add(key);
            Map<String,Object> entry=entry(key),item=new LinkedHashMap<>();
            item.put("criterion","待核对"+entry.get("label")+"完整要求");item.put("status","needs_evidence");
            item.put("evidence","");item.put("verified",false);item.put("requirement_source_span",entry.get("canonical_span"));
            item.put("requirement_field_source",fieldSource(entry));item.put("requirement_text_source","requirement_canonical");checks.add(item);
        }
        candidate.put("requirement_field_assessment",fieldAssessments);
        candidate.put("unresolved_guided_fields",unresolvedFields);
        candidate.put("requirement_coverage",checks);
        // Future recipients have their own non-hard audit. Attach field
        // provenance without inserting them into the hard-check list.
        if(candidate.get("audience_context") instanceof List<?> rawContexts) {
            List<Map<String,Object>> contexts=new ArrayList<>();
            for(Object value:rawContexts) if(value instanceof Map<?,?> rawContext) {
                Map<String,Object> item=new LinkedHashMap<>((Map<String,Object>)rawContext);
                if(item.get("requirement_source_span") instanceof Map<?,?> existing) {
                    try {
                        int left=canonicalText.offsetByCodePoints(0,((Number)existing.get("source_start")).intValue());
                        int right=canonicalText.offsetByCodePoints(0,((Number)existing.get("source_end")).intValue());
                        // Only relabel a range that still identifies the exact
                        // canonical characters; never assume normalization is free.
                        if(canonicalText.substring(left,right).equals(existing.get("text"))) {
                            Map<String,Object> originSpan=new LinkedHashMap<>((Map<String,Object>)existing);
                            originSpan.put("source","requirement_canonical");
                            item.put("requirement_source_span",originSpan);
                            item.put("requirement_canonical_text",canonicalText);
                            item.put("requirement_text_source","requirement_canonical");
                            List<Map<String,Object>> origins=origins(originSpan);
                            item.put("requirement_field_sources",origins);
                            if(origins.size()==1)item.put("requirement_field_source",origins.get(0));
                        }
                    } catch(IndexOutOfBoundsException|ClassCastException|NullPointerException invalidRange) {
                        // Keep the original matching-text audit, not invented field offsets.
                    }
                }
                contexts.add(item);
            }
            candidate.put("audience_context",contexts);
        }
        if(candidate.get("unparsed_requirements") instanceof List<?> raw) {
            List<Map<String,Object>> remapped=new ArrayList<>();
            for(Object item:raw) if(item instanceof Map<?,?>) {
                Map<String,Object> span=new LinkedHashMap<>((Map<String,Object>)item);span.put("source","requirement_canonical");
                span.put("field_sources",origins(span));remapped.add(span);
            }
            candidate.put("unparsed_requirements",remapped);
        }
        if(!unresolvedFields.isEmpty()) {
            if(Boolean.TRUE.equals(candidate.get("_admitted"))) candidate.put("_review_candidate",true);
            candidate.put("_admitted",false);candidate.put("coverage_complete",false);candidate.put("match_tier","needs_review");
            List<String> gaps=new ArrayList<>((List<String>)candidate.getOrDefault("gaps",List.of()));
            gaps.add("补充要求及其硬性约束尚未完整结构化："+String.join("、",unresolvedFields.stream().map(k->Objects.toString(entry(k).get("label"))).toList())+"，已保留原文，请核对后再确定人选");
            candidate.put("gaps",gaps);
        }
    }
    /** A candidate-local receipt for an existing, complete proof, not another
     * requirement parser. It can only omit our duplicate gate; it never sets
     * _admitted or coverage_complete to true and never mutates the input ledger. */
    private Map<String,Object> completeFieldSupport(String key,Map<String,Object> candidate,List<Map<String,Object>> checks,String effectiveSource) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("field_path","requirement_contract.fields."+key);
        result.put("requirement_field_source",fieldSource(entry(key)));
        result.put("duplicate_gate_discharged",false);
        result.put("status","requires_review");
        result.put("reason","no_complete_supported_existing_condition");
        if(!List.of("preference","extra").contains(key))return result;
        // RequirementCoverage's topic ledger retains its input verbatim. The
        // constraints layer may mask operational fields separately; that view
        // must not replace the canonical source coordinates used here.
        String matching=canonicalText;
        if(!(candidate.get("requirement_topic_contract") instanceof Map<?,?> audit) ||
                !RequirementTopic.VERSION.equals(audit.get("schema_version")) || !matching.equals(audit.get("source")))return result;
        RequirementTopic.Result parsed=RequirementTopic.parse(matching);
        if(parsed.courses().size()!=1)return result;
        RequirementTopic.Course course=parsed.courses().get(0);
        Map<?,?> fieldSpan=(Map<?,?>)entry(key).get("canonical_span");
        int left=canonicalText.offsetByCodePoints(0,((Number)fieldSpan.get("source_start")).intValue());
        int right=canonicalText.offsetByCodePoints(0,((Number)fieldSpan.get("source_end")).intValue());
        String value=field(key);
        if(!canonicalText.substring(left,right).equals(value) || matching.length()!=canonicalText.length() ||
                !matching.substring(left,right).equals(value))return result;
        // Only one closed clause is in scope. Titles are opaque data, not
        // separators. Unknown qualifications, dates or additional clauses are
        // not discharged merely because another part of the field is known.
        String outsideTitle=value.replace("《"+course.canonicalAnchor()+"》","").replaceFirst("[。！？!?]+$","");
        if(outsideTitle.matches("(?s).*[，,。；;！？!?、].*") || outsideTitle.matches("(?s).*(?:20\\d{2}|\\d+[年月日]|资格|资质|证书|持证|认证).*") )return result;
        List<Map<String,Object>> proofs=new ArrayList<>();
        for(RequirementTopic.Consumption consumed:parsed.consumedSpans()) {
            if(!consumed.kind().equals("teaching_history_condition") || !consumed.courseIds().equals(List.of(course.id())))continue;
            RequirementTopic.Span s=consumed.span();
            if(s.start()<left || s.end()>right || !matching.substring(s.start(),s.end()).equals(s.text()) ||
                    !punctuationOnly(matching.substring(left,s.start())+matching.substring(s.end(),right)))continue;
            for(Map<String,Object> check:checks)if(course.id().equals(check.get("course_id"))&&supported(check)&&
                    ("课程授课经历："+course.canonicalAnchor()).equals(check.get("criterion"))&&
                    historyCarries(check,s,matching)&&sameCourseContent(checks,course)&&
                    fullSourceProof(check,candidate,effectiveSource)!=null)proofs.add(check);
        }
        String kind="bound_course_history";
        if(proofs.isEmpty()) {
            AudienceIntent intent=new AudienceIntent(matching,parsed);
            for(Map<String,Object> check:checks) {
                String criterion=Objects.toString(check.get("criterion"),"");
                if(!criterion.startsWith("授课对象：")||!intent.hard.containsKey(criterion)||!supported(check))continue;
                String target=criterion.substring("授课对象：".length());
                Map<String,Object> record=fullSourceProof(check,candidate,effectiveSource);
                if(record==null||!closedFit(value,course.canonicalAnchor(),target)||!sameCourseContent(checks,course))continue;
                Map<String,List<String>> scope=Map.of(course.canonicalAnchor(),List.of(course.canonicalAnchor()));
                boolean sameScope=TeachingEvents.structured(record)?
                        TeachingEvents.supportsTopic(record,scope.get(course.canonicalAnchor()))&&TeachingEvents.supportsFeature(record,criterion,intent.hard.get(criterion)):
                        RequirementConstraints.featureScoped(Objects.toString(record.get("text")),scope,intent.hard.get(criterion));
                if(sameScope)proofs.add(check);
            }
            kind="closed_explicit_audience_fit";
        }
        if(!proofs.isEmpty()) {
            result.put("duplicate_gate_discharged",true);result.put("status","existing_condition_supported");
            result.put("reason",kind);result.put("course_id",course.id());
            result.put("canonical_span",fieldSpan);
            result.put("supported_criteria",proofs.stream().map(p->p.get("criterion")).distinct().toList());
            result.put("source_scopes",proofs.stream().map(p->{Map<String,Object> record=fullSourceProof(p,candidate,effectiveSource);return Map.of(
                    "evidence_id",record.get("evidence_id"),"source_offset",record.get("source_offset"),"offset_unit","java_utf16_code_unit",
                    "source","current_effective_matching_source","role",record.get("role"));}).toList());
            result.put("notice","完整字段由现有条件及同课程证据承载；仅撤去重复整字段待核，不改变原匹配结论或外部资质真实性。");
        }
        return result;
    }
    private static boolean punctuationOnly(String text) {return text.matches("[\\s。！？!?]*");}
    private static boolean supported(Map<String,Object> check) {
        return "source_supported".equals(check.get("status"))&&check.get("evidence") instanceof String evidence&&!evidence.isBlank();
    }
    private static boolean historyCarries(Map<String,Object> check,RequirementTopic.Span consumed,String matching) {
        if(!(check.get("history_conditions") instanceof List<?> conditions))return false;
        for(Object item:conditions)if(item instanceof Map<?,?> condition&&condition.get("source_span") instanceof Map<?,?> span) {
            try {
                int left=matching.offsetByCodePoints(0,((Number)span.get("source_start")).intValue());
                int right=matching.offsetByCodePoints(0,((Number)span.get("source_end")).intValue());
                if(left==consumed.start()&&right==consumed.end()&&matching.substring(left,right).equals(span.get("text")))return true;
            }catch(IndexOutOfBoundsException|ClassCastException|NullPointerException invalidSpan){return false;}
        }
        return false;
    }
    private static boolean sameCourseContent(List<Map<String,Object>> checks,RequirementTopic.Course course) {
        for(Map<String,Object> content:checks)if(supported(content)&&
                List.of(course.canonicalAnchor(),"指定课程："+course.canonicalAnchor(),"培训主题："+course.canonicalAnchor()).contains(content.get("criterion")))return true;
        return false;
    }
    /** Recover the actual existing full-source unit, never parse the displayed
     * quote as a new resume. Manual-profile removals remain authoritative. */
    private static Map<String,Object> fullSourceProof(Map<String,Object> proof,Map<String,Object> candidate,String effectiveSource) {
        if(effectiveSource==null)return null;
        String source=ProfessionalEvidence.normalized(effectiveSource),quote=Objects.toString(proof.get("evidence"),"");
        if(quote.isBlank()||quote.contains("…")||quote.contains("..."))return null;
        Map<?,?> semantic=candidate.get("semantic") instanceof Map<?,?> m?m:null;
        boolean scoped=semantic!=null&&Boolean.FALSE.equals(semantic.get("evidence_complete"));
        if(scoped&&!EvidenceSections.verified(source,semantic))return null;
        List<Map<String,Object>> records;
        if(scoped&&EvidenceSections.MIXED_VERSION.equals(semantic.get("scoring_scope")))
            records=EvidenceSections.mixedEvidence(source,semantic).records();
        else {
            List<TeachingEvents.Event> events=TeachingEvents.extract(source).stream().filter(TeachingEvents.Event::explicitBlock).toList();
            records=new ArrayList<>(ProfessionalEvidence.records(source).stream().filter(r->{
                int at=((Number)r.get("source_offset")).intValue();
                return events.stream().noneMatch(e->at>=e.start&&at<e.end&&!e.form.equals("narrative"));
            }).toList());
            for(TeachingEvents.Event event:events)if(event.personalLead())records.add(event.record());
        }
        LinkedHashMap<Integer,Map<String,Object>> found=new LinkedHashMap<>();
        for(Map<String,Object> record:records) {
            if(!quote.equals(record.get("text")) || List.of("organization_statement","client_statement").contains(record.get("role")))continue;
            int start=((Number)record.get("source_offset")).intValue(),end=start+quote.length();
            if(start<0||end>source.length()||!source.substring(start,end).equals(quote))continue;
            if(scoped) {
                int a=source.codePointCount(0,start),b=source.codePointCount(0,end);boolean included=false;
                for(Object raw:(List<?>)semantic.get("scoring_sections"))if(raw instanceof Map<?,?> s&&
                        ((Number)s.get("source_start")).intValue()<=a&&b<=((Number)s.get("source_end")).intValue()){included=true;break;}
                if(!included)continue;
            }
            // A short string is not evidence of completeness; exact equality
            // with a full-source unit and its real range is required, including
            // at the 220-character display boundary. Repeated positions are ambiguous.
            Map<String,Object> old=found.get(start);
            if(old==null||TeachingEvents.structured(record))found.put(start,record);
        }
        return found.size()==1?found.values().iterator().next():null;
    }
    private static boolean closedFit(String raw,String anchor,String target) {
        String text=raw.strip().replaceFirst("[。！？!?]+$","");
        String title="《"+anchor+"》";
        if(text.startsWith(title))text=text.substring(title.length()).strip();
        // Reconstruct the ENTIRE already-typed clause. AudienceIntent.target
        // may trim syntax, but that must never erase an unknown suffix here.
        for(String subject:List.of("","课程","本次课程","本课程","该课程","这门课程","这一门课程","教学","培训","客户","我们"))
            for(String modal:List.of("必须","要求","需要","需","应","须"))
                for(String can:List.of("","能够"))for(String verb:List.of("适配","适合","面向","针对"))
                    if(text.equals(subject+modal+can+verb+target))return true;
        return false;
    }
    private Map<String,Object> entry(String key) { return ledger.stream().filter(e->key.equals(e.get("field"))).findFirst().orElseThrow(); }
    private List<Map<String,Object>> origins(Map<String,Object> span) {
        if(!(span.get("source_start") instanceof Number left) || !(span.get("source_end") instanceof Number right)) return List.of();
        List<Map<String,Object>> found=new ArrayList<>();
        for(Map<String,Object> entry:ledger) if(entry.get("canonical_span") instanceof Map<?,?> other &&
                left.intValue()<((Number)other.get("source_end")).intValue() && right.intValue()>((Number)other.get("source_start")).intValue()) found.add(fieldSource(entry));
        return found;
    }
    private static Map<String,Object> fieldSource(Map<String,Object> entry) {
        return Map.of("field_path",entry.get("field_path"),"raw_value",entry.get("raw_value"),
                "original_span",entry.get("original_span"),"canonical_span",entry.get("canonical_span"));
    }
    private static Map<String,Object> span(String text,int start,int end,String source) {
        return Map.of("text",text.substring(start,end),"source_start",text.codePointCount(0,start),"source_end",text.codePointCount(0,end),
                "offset_unit","unicode_code_point","source",source);
    }
    private static String trim(String value) { return value.replaceFirst("^"+JS_SPACE+"+","").replaceFirst(JS_SPACE+"+$",""); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
