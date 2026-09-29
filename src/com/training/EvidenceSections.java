package com.training;

import java.util.*;
import java.util.regex.*;

/** A narrowly scoped, independently checked exception to whole-document
 * incompleteness. Scores are retrieval only; requirements still need evidence.
 * Offsets are Unicode code points in the exact normalized request source. */
final class EvidenceSections {
    static final String VERSION="independent_sections_v1";
    static final String FACT_VERSION="independent_facts_v2";
    static final String PROFILE_VERSION="independent_profiles_v1";
    static final String MIXED_VERSION="mixed_source_scopes_v1";
    private static final String PROFILE_KIND="leading_profile_list_v1";
    private static final Pattern PROFILE_ITEM=Pattern.compile("([✓✔☑•●▪])[\\t ]*(\\S(?:[^\\r\\n]{0,78}\\S)?)");
    // Source discourse/actor operators, not a course-domain vocabulary. A
    // headingless noun list cannot resolve a later ownership reference.
    private static final Pattern PROFILE_REFERENCE=Pattern.compile("以上|上述|上列|前述|前页|这些|此处|这里|本页|全文|所有|全部|以下|下列|后文|后页|开头|顶部|上面的?|前面的?|勾选|清单|列表|列项|模板|范文|样例|转述|引用|摘录");
    private static final Pattern PROFILE_STATEMENT=Pattern.compile("^(?:本人|我|该讲师|该教师|他人|别人|同事)|主讲|讲授|授课|^(?:(?:已(?:经)?|曾(?:经)?|独立|亲自|计划|拟|待|未|准备|将|希望|打算)){0,3}(?:完成|参加|参与|参训|听课|取得|获得|持有|通过|颁发|授予|开设|开展|举办)|履历|经历|经验|简历|属于|来自|(?:本|所在|供职)(?:团队|机构|公司|单位)|(?:团队|机构|公司)(?:的|能力|专业|擅长|精通|领域)");
    private static final Pattern REFERENCE_QUALIFICATION=Pattern.compile("(?:以下|下列|后文|后页|本页|所有|全部|上述|前述|这些)[^。；;！？!?]{0,30}(?:计划|拟开|待开|筹备|仅参训|仅参加|参训记录)|(?:计划|拟开|筹备|参训|听课|他人|别人|其他老师|其他讲师)[^。；;！？!?]{0,30}(?:以下|下列|后文|后页|本页|所有|全部|上述|前述|这些)");
    private static final Pattern FACT_CONTEXT_RISK=Pattern.compile("(?:团队|机构|公司)(?:业绩|案例|完成|授课|主讲|累计)|(?:^|[\\n，,。；;])(?:本)?(?:团队|机构|公司)(?:已|曾|取得|持有|完成)|(?:以下|下列|上述|这些|全部|本页|本次|后续|所有)[^。；;！？!?]{0,24}(?:拟|计划|筹备|参训|听课|助教|他人|团队)|(?:拟|计划|参训|听课|助教|他人|团队)[^。；;！？!?]{0,24}(?:以下|下列|上述|这些|全部|本页|所有|课程清单)|(?:其中|前者|后者|该课程|此课程|该经历|上述|以上|前述)[^。；;！？!?]{0,60}(?:未|不|仅|只|待|他人|其他|团队)");
    private static final Pattern SCORING=Pattern.compile("(?:主讲课程|精品课程|课程列表|擅长领域|授课专长|授课领域|主讲方向|授课风采|教学经历|实际授课记录)[:：]?");
    private static final Pattern HEADING=Pattern.compile("(?:个人介绍|主讲课程|精品课程|课程列表|擅长领域|授课专长|授课领域|主讲方向|授课风采|教学经历|实际授课记录|团队业绩|机构业绩|团队案例|机构案例|公司案例|团队授课记录|未授课课程|未讲授课程|尚未开设课程|仅参训课程|参训经历|参加过的培训|助教经历|筹备课程|未来规划|计划课程|教育背景|资质认证|认证经历|工作经历|职业经历|项目经历|服务客户|客户名单|联系方式|个人职责|个人案例|本人经历)[:：]?");
    private static final Pattern TEMPLATE=Pattern.compile("(?:替换个人照片|请替换个人照片|点击(?:此处)?(?:添加|输入)(?:标题|文本)|[xX]{3,})[。.!！]?");
    private static final Pattern GLOBAL=Pattern.compile("(?:以上|上述|上行|前述|全文|所有|全部|这些|这些记录|该课程|本次|同一项目|前页)[^。；;！？!?]{0,80}(?:不代表|不表示|不是|并非|非本人|没有|未曾|从未|尚未|未承担|未主讲|未讲授|未由|均未|都未|待核|待确认|(?:只是|仅为|均为|均属)(?:团队|机构|宣传|样例))|(?:本人|我|该讲师)[^。；;，,！？!?]{0,20}(?:没有|从未|未曾|未承担|未主讲|未讲授|并非|不是)[^。；;！？!?]{0,40}(?:授课|主讲|讲授|教学|经历|完成)|(?:不代表|不表示)[^。；;！？!?]{0,20}(?:本人|个人|我)[^。；;！？!?]{0,20}(?:授课|教学|经历)");
    private static final Pattern NAMED_COURSE_DENIAL=Pattern.compile("(?:本人|我)(?:没有|从未|未曾|尚未|未)(?:亲自)?(?:主讲|讲授)(?:过)?《([^《》。；;，,！？!?\\r\\n]{2,80})》(?:这门课(?:程)?|课(?:程)?)?[。.!！]?");
    private static String globalQualificationView(String normalized) {
        // Only a complete named-course denial in an explicit excluded section
        // has local scope. Never edit source evidence, offsets, or contradictions.
        boolean excluded=false;List<String> view=new ArrayList<>();
        for(String raw:normalized.split("\\R",-1)) {
            String line=raw.strip();
            if(boundary(line)) excluded=line.matches("(?:未授课课程|未讲授课程)[:：]?");
            Matcher denial=NAMED_COURSE_DENIAL.matcher(line);
            if(excluded && denial.matches() && !PROFILE_REFERENCE.matcher(denial.group(1)).find())view.add("");
            else view.add(raw);
        }
        // An unresolved reference elsewhere can bind across section headings.
        // In that case retain the original global guard, including local lines.
        for(String line:view) if(!boundary(line.strip()) && PROFILE_REFERENCE.matcher(line).find())return normalized;
        return String.join("\n",view);
    }
    static boolean globalQualification(String source) {
        // Forward references are just as binding as backward references.
        String normalized=globalQualificationView(ProfessionalEvidence.polarityView(ProfessionalEvidence.normalized(source))).replaceAll("以下|下列|后文|后页","上述");
        if(Pattern.compile("(?:本人|我|该讲师)(?:仍|还)?(?:未完成|没有完成)(?:这门课(?:程)?|该课(?:程)?|本课(?:程)?)(?:的)?(?:教学|讲授|授课)(?:[。；;！？!?\\r\\n]|$)|上述(?:为|是)?(?:引用|转述|摘录)[^。；;\\r\\n]{1,60}(?:教学记录|授课经历|授课记录|履历)").matcher(normalized).find())return true;
        return GLOBAL.matcher(normalized).find() || REFERENCE_QUALIFICATION.matcher(normalized).find();
    }
    static boolean templateLine(String line) { return TEMPLATE.matcher(line.strip()).matches(); }
    static boolean boundary(String line) { return HEADING.matcher(line.strip()).matches(); }
    static boolean factContextSafe(String text) {
        text=ProfessionalEvidence.polarityView(text);
        return !globalQualification(text) && !FACT_CONTEXT_RISK.matcher(text).find() &&
            !ProfessionalEvidence.ORGANIZATION_EVENT.matcher(text).find() && !ProfessionalEvidence.otherActor(text);
    }
    private static boolean independentFactLine(String line) {
        return line.codePointCount(0,line.length())>=2 && line.codePointCount(0,line.length())<=220 &&
            ProfessionalEvidence.nonproofSpans(line).isEmpty() &&
            ProfessionalEvidence.allowed(line) && !ProfessionalEvidence.otherActor(line) &&
            !FACT_CONTEXT_RISK.matcher(line).find() && !ProfessionalEvidence.ORGANIZATION_EVENT.matcher(line).find() &&
            !line.matches("(?is).*(?:拟开设|计划开设|拟主讲|计划授课|拟任讲师|筹备|xxx|替换个人照片).*") &&
            !line.matches("(?s).*(?:参加|参与|参训|学习|听课|结业).{0,50}(?:课程|培训|认证|证书).*") &&
            !line.matches("(?s).*(?:助教|会务|报名|会场安排|资料运营|课程库管理|(?:仅|只)(?:负责|承担|参与|协助|提供|整理|汇总|安排)).*") &&
            (ProfessionalEvidence.completedPersonalEvent(line) || line.matches("(?s).*(?:主讲|讲授|授课|宣讲|课堂|擅长|精通|熟悉).*"));
    }
    private record SourceLine(String raw,int start,int end) {
        String text() { return raw.strip(); }
        int left() { return start+raw.length()-raw.stripLeading().length(); }
        int right() { return start+raw.stripTrailing().length(); }
    }
    private static Map<String,Object> fact(String source,SourceLine line,SourceLine heading,int contextStart,int contextEnd,String kind) {
        Map<String,Object> row=new LinkedHashMap<>(scope(source,heading.text(),heading.left(),heading.right(),line.left(),line.right()));
        row.put("context_start",codePoint(source,contextStart));row.put("context_end",codePoint(source,contextEnd));row.put("kind",kind);
        return row;
    }
    private static List<Map<String,Object>> independentFacts(String source) {
        List<SourceLine> lines=new ArrayList<>();List<Integer> boundaries=new ArrayList<>();
        Matcher matcher=Pattern.compile("[^\\r\\n]*(?:\\r\\n|\\r|\\n|$)").matcher(source);
        while(matcher.find()) {
            SourceLine line=new SourceLine(matcher.group(),matcher.start(),matcher.end());
            if(boundary(line.text())) boundaries.add(lines.size());lines.add(line);
        }
        List<Map<String,Object>> result=new ArrayList<>();
        for(int b=0;b<boundaries.size();b++) {
            int index=boundaries.get(b),next=b+1<boundaries.size()?boundaries.get(b+1):lines.size();
            SourceLine heading=lines.get(index);String title=heading.text().replaceFirst("[:：]$","");
            boolean roleRestricted=ProfessionalEvidence.ROLE_CONTEXT.matcher(source).find();
            if(!Set.of("个人介绍","个人职责","本人经历").contains(title) && !(roleRestricted && SCORING.matcher(title).matches())) continue;
            int start=heading.end(),end=next<lines.size()?lines.get(next).start():source.length();
            String context=source.substring(start,end);
            if(!factContextSafe(context)) continue;
            for(int i=index+1;i<next;i++) {
                SourceLine line=lines.get(i);
                if(line.text().isEmpty())continue;
                if(!ProfessionalEvidence.roleSafeAt(source,line.left(),line.right(),0,source.length()))continue;
                if(independentFactLine(line.text()))result.add(fact(source,line,heading,start,end,"professional_fact_v2"));
                else if(!roleRestricted && !ProfessionalEvidence.negative(context) && !PROFILE_REFERENCE.matcher(context).find())
                    for(String sentence:ProfessionalEvidence.capabilitySentences(line.text())) {
                        int left=line.left()+line.text().indexOf(sentence);
                        SourceLine part=new SourceLine(sentence,left,left+sentence.length());
                        result.add(fact(source,part,heading,start,end,"capability_sentence_v1"));
                    }
            }
            if(roleRestricted || next>=lines.size() || !lines.get(next).text().matches("(?:主讲课程|精品课程|课程列表)[:：]?")) continue;
            if(context.matches("(?s).*(?:拟|计划|筹备|参训|听课|他人|别人|助教|助理|[（(]筹[）)]).*") ||
                ProfessionalEvidence.negative(context) || context.matches("(?s).*(?:参加|参与|参训|学习|听课|结业).{0,50}(?:课程|培训|认证|证书).*")) continue;
            List<SourceLine> run=new ArrayList<>();
            for(int i=next-1;i>index;i--) {
                SourceLine line=lines.get(i);
                if(!line.text().matches("《[^》\\r\\n]{2,80}》")) break;
                run.add(line);
            }
            if(run.size()<2) continue;
            Collections.reverse(run);
            for(SourceLine line:run) result.add(fact(source,line,lines.get(next),start,end,"course_catalog_entry_v2"));
        }
        return result;
    }
    static String versionFor(List<Map<String,Object>> scopes) {
        if(scopes.stream().anyMatch(s->PROFILE_KIND.equals(s.get("kind"))))return PROFILE_VERSION;
        return scopes.stream().anyMatch(s->s.containsKey("kind"))?FACT_VERSION:VERSION;
    }
    /** An intact initial noun-label list, never an arbitrary prefix or an
     * invented section heading. The entire source remains qualification context.
     * Existing downstream record classification is retained, not upgraded. */
    private static List<Map<String,Object>> leadingProfile(String source) {
        String inspected=ProfessionalEvidence.polarityView(source);
        // Only structural heading lines are not discourse references. They
        // remain untouched in every full-source role/qualification guard.
        String references=String.join("\n",Arrays.stream(inspected.split("\\R",-1)).filter(line->!boundary(line.strip())).toList());
        if(globalQualification(source)||!factContextSafe(source)||ProfessionalEvidence.negative(inspected)||
            PROFILE_REFERENCE.matcher(references).find())return List.of();
        List<SourceLine> items=new ArrayList<>();String marker=null;boolean closed=false,gap=false;
        Matcher lines=Pattern.compile("[^\\r\\n]*(?:\\r\\n|\\r|\\n|$)").matcher(source);
        while(lines.find()) {
            SourceLine line=new SourceLine(lines.group(),lines.start(),lines.end());String text=line.text();
            if(text.isBlank()){if(!items.isEmpty())gap=true;continue;}
            if(items.isEmpty()&&ProfessionalEvidence.personalMetadataLine(text))continue;
            if(boundary(text)) {
                closed=items.size()>=2&&!text.matches("(?:团队.*|机构.*|公司.*|未.*|尚未.*|仅.*|参训.*|参加过.*|助教.*|筹备.*|未来.*|计划.*)");break;
            }
            Matcher item=PROFILE_ITEM.matcher(text);
            if(gap||!item.matches()||items.size()>=12)return List.of();
            String value=item.group(2);
            if(value.codePointCount(0,value.length())<2||value.codePointCount(0,value.length())>80||
                value.matches("(?s).*[:：，,。；;！？!?].*")||PROFILE_STATEMENT.matcher(value).find()||
                !ProfessionalEvidence.allowed(text)||ProfessionalEvidence.ownTeaching(text)||ProfessionalEvidence.isTeachingRecord(text,source))return List.of();
            if(marker==null)marker=item.group(1);else if(!marker.equals(item.group(1)))return List.of();
            items.add(line);
        }
        if(!closed)return List.of();
        int start=items.get(0).left(),end=items.get(items.size()-1).right();
        if(source.codePointCount(start,end)>220||!ProfessionalEvidence.roleSafeAt(source,start,end,0,source.length()))return List.of();
        Map<String,Object> row=new LinkedHashMap<>(scope(source,"",start,start,start,end));
        row.put("kind",PROFILE_KIND);row.put("context_start",0);row.put("context_end",codePoint(source,source.length()));
        return List.of(row);
    }
    static boolean denialConflicts(String source,Map<String,List<String>> required) {
        StringBuilder denied=new StringBuilder();boolean active=false;
        String normalized=ProfessionalEvidence.normalized(source);
        // Explicitly omitted course content remains a negative source claim.
        // It cannot support a requested facet through a broad course title.
        for(int[] span:ProfessionalEvidence.contentExclusionSpans(normalized))
            denied.append(normalized,span[0],span[1]).append('\n');
        for(String line:normalized.split("\\R")) {
            line=line.strip();
            if(boundary(line)) {
                active=line.matches("(?:未授课课程|未讲授课程|尚未开设课程|仅参训课程)[:：]?");
                continue;
            }
            if(active) denied.append(line).append('\n');
        }
        if(denied.toString().isBlank()) return false;
        // Unknown topics cannot be safely aligned with a contradictory catalog.
        if(required.isEmpty()) return true;
        for(List<String> variants:required.values()) for(String variant:variants)
            if(denied.indexOf(variant)>=0) return true;
        return false;
    }
    private static int codePoint(String source,int utf16) { return source.codePointCount(0,utf16); }
    private static boolean safeBody(String raw) {
        List<String> kept=new ArrayList<>();
        for(String line:raw.split("\\R")) if(!templateLine(line) && !ProfessionalEvidence.personalMetadataLine(line.strip())) kept.add(line);
        String body=String.join("\n",kept).strip();
        if(body.isEmpty() || globalQualification(body) || !ProfessionalEvidence.allowed(body) || ProfessionalEvidence.ROLE_CONTEXT.matcher(body).find() ||
            body.matches("(?is).*(?:xxx|替换个人照片|筹备|拟开设|计划开设|拟主讲|计划授课|拟任讲师|[（(]筹[）)]).*")) return false;
        if(ProfessionalEvidence.polarityView(body).matches("(?s).*(?:团队|机构业绩|公司案例|其中|上述|以上|前述|同一项目|本次|部分|剩余|前者|后者|其他.{0,12}(?:主讲|讲授|授课)).*")) return false;
        for(String paragraph:body.split("(?:\\r?\\n)[\\t ]*(?:\\r?\\n)")) {
            if(paragraph.codePointCount(0,paragraph.length())<=220) continue;
            // Match the worker's complete short catalog-line split. Any unknown
            // long prose is deliberately not accepted merely because it fits.
            String[] lines=paragraph.split("\\R");
            if(lines.length<2) return false;
            for(String line:lines) {
                line=line.strip();
                if(line.codePointCount(0,line.length())>220 ||
                    line.matches("(?s).*(?:其中|上述|以上|前述|本次|该课程|同一|部分|剩余|但是|然而|团队|机构|公司).*")) return false;
                if(line.matches("(?s).*[，,；;。！？!?].*") && !ProfessionalEvidence.ownTeaching(line)) return false;
            }
        }
        return true;
    }
    static List<Map<String,Object>> sections(String input) {
        String source=LocalSemantic.normalized(input);
        if(globalQualification(source)) return List.of();
        List<Map<String,Object>> result=new ArrayList<>();
        boolean roleRestricted=ProfessionalEvidence.ROLE_CONTEXT.matcher(globalQualificationView(source)).find();
        Matcher lines=Pattern.compile("[^\\r\\n]*(?:\\r\\n|\\r|\\n|$)").matcher(source);
        int body=-1,headingStart=0,headingEnd=0;String heading="";
        while(lines.find()) {
            String raw=lines.group(),line=raw.strip();
            if(!boundary(line)) continue;
            if(!roleRestricted && body>=0 && safeBody(source.substring(body,lines.start())) &&
                    ProfessionalEvidence.sameTeacherContextSafe(source.substring(body,lines.start()),source))
                result.add(scope(source,heading,headingStart,headingEnd,body,lines.start()));
            body=-1;
            if(SCORING.matcher(line).matches()) {
                heading=line;headingStart=lines.start()+raw.length()-raw.stripLeading().length();
                headingEnd=lines.start()+raw.stripTrailing().length();body=lines.end();
            }
        }
        if(!roleRestricted && body>=0 && safeBody(source.substring(body)) && ProfessionalEvidence.sameTeacherContextSafe(source.substring(body),source))
            result.add(scope(source,heading,headingStart,headingEnd,body,source.length()));
        result.addAll(independentFacts(source));result.addAll(leadingProfile(source));return result;
    }
    private static Map<String,Object> scope(String source,String heading,int hs,int he,int start,int end) {
        return Map.of("heading",heading,"heading_start",codePoint(source,hs),"heading_end",codePoint(source,he),
            "source_start",codePoint(source,start),"source_end",codePoint(source,end),"offset_unit","unicode_code_point");
    }
    static boolean verified(String source,Map<?,?> details) {
        if(MIXED_VERSION.equals(details.get("scoring_scope")))return verifiedMixed(source,details);
        if(TeachingEvents.SCORING_SCOPE.equals(details.get("scoring_scope")))return TeachingEvents.verifiedScoringSections(source,details);
        if(!Boolean.TRUE.equals(details.get("scoped_evidence_complete")) ||
            !Set.of(VERSION,FACT_VERSION,PROFILE_VERSION).contains(details.get("scoring_scope")) || !(details.get("scoring_sections") instanceof List<?> supplied) ||
            supplied.isEmpty() || supplied.size()>64) return false;
        boolean hasProfile=supplied.stream().anyMatch(s->s instanceof Map<?,?> row&&PROFILE_KIND.equals(row.get("kind")));
        if(PROFILE_VERSION.equals(details.get("scoring_scope"))!=hasProfile)return false;
        List<Map<String,Object>> expected=sections(source);
        // Numeric JSON types differ (Long/Integer), so compare canonical fields
        // explicitly, and require every claimed range to be independently safe.
        Set<String> seen=new HashSet<>();
        for(Object raw:supplied) {
            if(!(raw instanceof Map<?,?> row)) return false;
            boolean found=false;
            for(Map<String,Object> known:expected) {
                if(!Objects.equals(row.get("heading"),known.get("heading")) || !Objects.equals(row.get("kind"),known.get("kind")) ||
                    row.containsKey("kind") && !Set.of(FACT_VERSION,PROFILE_VERSION).contains(details.get("scoring_scope")) || !"unicode_code_point".equals(row.get("offset_unit"))) continue;
                boolean exact=true;
                for(String key:List.of("heading_start","heading_end","source_start","source_end")) {
                    Object n=row.get(key);
                    exact &= n instanceof Number && Double.isFinite(((Number)n).doubleValue()) &&
                        ((Number)n).doubleValue()==((Number)known.get(key)).longValue();
                }
                if(known.containsKey("kind")) for(String key:List.of("context_start","context_end")) {
                    Object n=row.get(key);
                    exact &= n instanceof Number && Double.isFinite(((Number)n).doubleValue()) &&
                        ((Number)n).doubleValue()==((Number)known.get(key)).longValue();
                }
                if(exact) { found=seen.add(known.get("heading_start")+":"+known.get("source_end"));break; }
            }
            if(!found) return false;
        }
        return true;
    }
    private static boolean verifiedMixed(String source,Map<?,?> details) {
        if(!Boolean.FALSE.equals(details.get("evidence_complete"))||!Boolean.TRUE.equals(details.get("scoped_evidence_complete"))||
            !(details.get("scoring_sections") instanceof List<?> supplied)||supplied.isEmpty()||supplied.size()>64)return false;
        List<Map<String,Object>> events=new ArrayList<>(),independent=new ArrayList<>();
        for(Object raw:supplied) {
            if(!(raw instanceof Map<?,?> row))return false;
            boolean event=TeachingEvents.SCORING_SCOPE.equals(row.get("kind"));
            Set<String> keys=event?Set.of("event_id","heading","heading_start","heading_end","source_start","source_end","kind","offset_unit"):
                row.containsKey("kind")?Set.of("heading","heading_start","heading_end","source_start","source_end","kind","context_start","context_end","offset_unit"):
                Set.of("heading","heading_start","heading_end","source_start","source_end","offset_unit");
            if(!row.keySet().equals(keys)||!event&&row.containsKey("kind")&&
                (!(row.get("kind") instanceof String kind)||!Set.of("professional_fact_v2","course_catalog_entry_v2","capability_sentence_v1",PROFILE_KIND).contains(kind)))return false;
            Map<String,Object> copy=new LinkedHashMap<>();row.forEach((key,value)->copy.put((String)key,value));
            (event?events:independent).add(copy);
        }
        if(events.isEmpty()||independent.isEmpty())return false;
        // Each family is checked against the WHOLE original source by its
        // established validator. A union is never a new synthesized resume.
        return TeachingEvents.verifiedScoringSections(source,Map.of("scoped_evidence_complete",true,
                "scoring_scope",TeachingEvents.SCORING_SCOPE,"scoring_sections",events))&&
            verified(source,Map.of("scoped_evidence_complete",true,"scoring_scope",versionFor(independent),"scoring_sections",independent));
    }
    private static int integer(Object value,int lower,int upper) {
        if(!(value instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()<lower||n.doubleValue()>upper||n.doubleValue()!=n.intValue())return -1;
        return n.intValue();
    }
    private static boolean rangeIncluded(String source,Map<?,?> details,int start,int end) {
        for(Object raw:(List<?>)details.get("scoring_sections")) {
            Map<?,?> row=(Map<?,?>)raw;
            if(((Number)row.get("source_start")).intValue()<=start&&end<=((Number)row.get("source_end")).intValue())return true;
        }
        return false;
    }
    /** Retrieval coordinates must identify one literal unit inside ONE range;
     * a quote cannot straddle a gap or borrow a different identical occurrence. */
    static boolean containsUnit(String input,Map<?,?> details,Map<?,?> unit) {
        if(unit==null||details==null||!verified(input,details)||!"unicode_code_point".equals(unit.get("offset_unit"))||
            !"retrieval_only".equals(unit.get("kind"))||!(unit.get("text") instanceof String quote))return false;
        String source=LocalSemantic.normalized(input);int length=source.codePointCount(0,source.length());
        int start=integer(unit.get("source_start"),0,length),end=integer(unit.get("source_end"),0,length);
        int contextStart=integer(unit.get("paragraph_start"),0,length),contextEnd=integer(unit.get("paragraph_end"),0,length);
        if(start<0||end-start<2||end-start>220||contextStart<0||contextStart>start||contextEnd<end||
            !quote.equals(source.substring(source.offsetByCodePoints(0,start),source.offsetByCodePoints(0,end)))||
            !rangeIncluded(source,details,start,end))return false;
        Object status=unit.get("context_status");
        if(!(status instanceof String)||!Set.of("paragraph_preserved","self_contained_sentences_after_paragraph_review","independent_fact_after_section_review","server_source_event").contains(status))return false;
        if("paragraph_preserved".equals(status)&&(start!=contextStart||end!=contextEnd))return false;
        if(!unit.containsKey("section_heading"))return false;
        Object heading=unit.get("section_heading");
        if(heading!=null&&(!(heading instanceof String)||((String)heading).length()>440))return false;
        // Empty leading-list titles and a catalog's following heading are
        // legitimate only through their already revalidated literal scopes.
        // Do not infer title ownership from any occurrence elsewhere in source.
        for(Object raw:(List<?>)details.get("scoring_sections")) {
            Map<?,?> scope=(Map<?,?>)raw;
            if(((Number)scope.get("source_start")).intValue()<=start&&end<=((Number)scope.get("source_end")).intValue()&&
                (heading==null?"".equals(scope.get("heading")):heading.equals(scope.get("heading"))))return true;
        }
        return false;
    }
    record MixedEvidence(List<String> units,List<Map<String,Object>> records,List<TeachingEvents.Event> events) {}
    private static boolean catalogContentOnly(String source,Map<?,?> details,int start,int end,String text) {
        // Re-evaluate the same literal record without a distant heading. The
        // existing standalone history predicate must carry completion itself.
        if(ProfessionalEvidence.ownTeaching(text)&&ProfessionalEvidence.isTeachingRecord(text,text))return false;
        int a=source.codePointCount(0,start),b=source.codePointCount(0,end);
        for(Object raw:(List<?>)details.get("scoring_sections")) {
            Map<?,?> scope=(Map<?,?>)raw;String kind=Objects.toString(scope.get("kind"),"");
            boolean catalog=Set.of(PROFILE_KIND,"course_catalog_entry_v2").contains(kind)||
                Objects.toString(scope.get("heading"),"").matches("(?:主讲课程|精品课程|课程列表|擅长领域|授课专长|授课领域|主讲方向)[:：]?");
            if(catalog&&((Number)scope.get("source_start")).intValue()<=a&&b<=((Number)scope.get("source_end")).intValue())return true;
        }
        return false;
    }
    /** Parse before filtering. Removing another block must never manufacture a
     * pronoun antecedent, actor, completion, or neighbouring feature binding. */
    static MixedEvidence mixedEvidence(String input,Map<?,?> details) {
        if(!MIXED_VERSION.equals(details.get("scoring_scope"))||!verified(input,details))
            return new MixedEvidence(List.of(),List.of(),List.of());
        String source=LocalSemantic.normalized(input);
        List<TeachingEvents.Event> allEvents=TeachingEvents.extract(source).stream().filter(TeachingEvents.Event::explicitBlock).toList();
        Map<String,TeachingEvents.Event> selected=new LinkedHashMap<>();
        for(TeachingEvents.Event event:allEvents)if(event.personalLead()&&rangeIncluded(source,details,
            source.codePointCount(0,event.start),source.codePointCount(0,event.end)))selected.putIfAbsent(event.id,event);
        List<Map<String,Object>> records=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(Map<String,Object> record:ProfessionalEvidence.records(source)) {
            int start=((Number)record.get("source_offset")).intValue();String text=(String)record.get("text");int end=start+text.length();
            if(!rangeIncluded(source,details,source.codePointCount(0,start),source.codePointCount(0,end))||
                allEvents.stream().anyMatch(event->start>=event.start&&start<event.end&&!event.form.equals("narrative")))continue;
            // A distant historical heading is not an event. Keep the original
            // classifier label for audit, but enforce this scope's content-only
            // limit unless this same record contains a personal teaching act.
            if("teaching_history_statement".equals(record.get("role"))&&catalogContentOnly(source,details,start,end,text)) {
                record=new LinkedHashMap<>(record);record.put("source_record_role",record.get("role"));
                record.put("role","course_statement");record.put("scope_evidence_limit","content_only");
            }
            if(seen.add(start+":"+end+":"+record.get("role")))records.add(record);
        }
        // Narrative legacy facts remain additive: the established event parser
        // may not carry every literal mode/activity. They are not new events;
        // event identities are counted once and no record counts are summed.
        for(TeachingEvents.Event event:selected.values())records.add(event.record());
        return new MixedEvidence(records.stream().map(r->(String)r.get("text")).distinct().toList(),records,List.copyOf(selected.values()));
    }
    static boolean containsQuote(String input,Map<?,?> details,String quote) {
        if(!verified(input,details)) return false;
        String source=LocalSemantic.normalized(input);
        for(Object raw:(List<?>)details.get("scoring_sections")) {
            Map<?,?> row=(Map<?,?>)raw;
            int start=source.offsetByCodePoints(0,((Number)row.get("source_start")).intValue());
            int end=source.offsetByCodePoints(0,((Number)row.get("source_end")).intValue());
            if(source.substring(start,end).contains(quote)) return true;
        }
        return false;
    }
    static String maskedSource(String input,Map<?,?> details) {
        // Mixed consumers must use the full-source record/event view. Exposing
        // a synthetic union string would invite a later caller to rebind it.
        if(MIXED_VERSION.equals(details.get("scoring_scope")))return "";
        if(!verified(input,details)) return "";
        String source=LocalSemantic.normalized(input);char[] masked=source.toCharArray();
        for(int i=0;i<masked.length;i++) if(masked[i]!='\r' && masked[i]!='\n') masked[i]=' ';
        for(Object raw:(List<?>)details.get("scoring_sections")) {
            Map<?,?> row=(Map<?,?>)raw;
            int headingStart=source.offsetByCodePoints(0,((Number)row.get("heading_start")).intValue());
            int headingEnd=source.offsetByCodePoints(0,((Number)row.get("heading_end")).intValue());
            for(int i=headingStart;i<headingEnd;i++) masked[i]=source.charAt(i);
            int start=source.offsetByCodePoints(0,((Number)row.get("source_start")).intValue());
            int end=source.offsetByCodePoints(0,((Number)row.get("source_end")).intValue());
            for(int i=start;i<end;i++) masked[i]=source.charAt(i);
        }
        return new String(masked);
    }
    private EvidenceSections() {}
}
