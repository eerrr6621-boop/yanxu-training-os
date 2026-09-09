package com.training;

import java.util.*;
import java.util.regex.*;

/** Source-bounded, explicit resume field blocks. Not an LLM, credential check,
 * or a parser for arbitrary prose. Unsupported fields/context remain visible. */
final class TeachingEvents {
    static final String VERSION="source_teaching_event_v1";
    static final String SCORING_SCOPE="source_teaching_events_v1";
    private static final Map<String,String> LABELS=Map.ofEntries(
        Map.entry("课程名称","course"),Map.entry("课程主题","course"),Map.entry("培训主题","course"),Map.entry("课程","course"),
        Map.entry("授课人","actor"),Map.entry("主讲人","lead_actor"),Map.entry("讲师","actor"),
        Map.entry("授课角色","role"),Map.entry("本人角色","role"),
        Map.entry("完成状态","completion"),Map.entry("授课状态","completion"),Map.entry("完成情况","completion"),
        Map.entry("培训对象","audience"),Map.entry("授课对象","audience"),Map.entry("参训对象","audience"),Map.entry("学员对象","audience"),Map.entry("本班对象","audience"),
        Map.entry("授课形式","mode"),Map.entry("教学形式","mode"),Map.entry("授课语言","language"),
        Map.entry("已完成场次","count"),Map.entry("授课时长","duration"),Map.entry("每场时长","each_duration"),
        Map.entry("学员活动","learner_activity"),Map.entry("每场活动","each_activity"),
        Map.entry("授课日期","date"),Map.entry("客户单位","client"),Map.entry("授课地点","location"));
    private static final Pattern FIELD=Pattern.compile("^([^:：]{1,16})[:：]\\s*(.*)$");
    record Field(String label,String value,int start,int end) {
        Map<String,Object> map(String source) {
            return Map.of("label",label,"value",value,"source_start",source.codePointCount(0,start),"source_end",source.codePointCount(0,end),
                "offset_unit","unicode_code_point","source","normalized_resume","text",source.substring(start,end));
        }
    }
    static final class Event {
        final String source,id;final int start;int end;
        final Map<String,Field> fields=new LinkedHashMap<>();final List<String> issues=new ArrayList<>();
        final List<Map<String,Object>> unparsedContext=new ArrayList<>();
        String form="field_block";
        String completionScope="";
        boolean narrativeLead,narrativeCompleted,narrativeIndependent;
        final List<Map<String,Object>> relations=new ArrayList<>();
        Event(String source,int start) {this.source=source;this.start=start;this.end=start;id="event-"+start;}
        String value(String name) {Field f=fields.get(name);return f==null?"":f.value;}
        boolean usable() {return issues.isEmpty()&&!value("course").isBlank();}
        boolean explicitBlock() {return form.equals("narrative")||fields.keySet().stream().anyMatch(Set.of("actor","lead_actor","role","completion")::contains);}
        boolean personalLead() {
            String actor=value("actor"),lead=value("lead_actor");
            boolean self=Set.of("本人","我","该讲师","该教师").contains(actor.isEmpty()?lead:actor);
            boolean role=Set.of("主讲","独立主讲","讲授","授课教师").contains(value("role"))||!lead.isEmpty();
            return usable()&&(self&&role||narrativeLead);
        }
        boolean completedPersonalTeaching() {return personalLead()&&(narrativeCompleted||Set.of("已完成","已结课","已授课","已完成授课").contains(value("completion")));}
        Map<String,Object> map() {
            Map<String,Object> fieldMap=new LinkedHashMap<>();fields.forEach((key,value)->fieldMap.put(key,value.map(source)));
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("schema_version",VERSION);result.put("event_id",id);result.put("fields",fieldMap);
            result.put("event_form",form);result.put("relations",List.copyOf(relations));
            if(!completionScope.isEmpty())result.put("completion_scope",completionScope);
            result.put("independent_lead",narrativeIndependent||value("role").equals("独立主讲"));
            result.put("source_start",source.codePointCount(0,start));result.put("source_end",source.codePointCount(0,end));
            result.put("offset_unit","unicode_code_point");result.put("source","normalized_resume");result.put("text",source.substring(start,end));
            result.put("source_normalization","NFKC; offsets are not original file offsets");
            result.put("issues",List.copyOf(issues));result.put("completed_personal_teaching",completedPersonalTeaching());
            result.put("unparsed_context",List.copyOf(unparsedContext));
            result.put("verified",false);return result;
        }
        Map<String,Object> record() {
            Map<String,Object> record=new LinkedHashMap<>();record.put("evidence_id",id);record.put("text",source.substring(start,end));
            record.put("source_offset",start);record.put("source_line",1+(int)source.substring(0,start).chars().filter(c->c=='\n').count());
            record.put("role",completedPersonalTeaching()?"teaching_history_statement":"course_statement");
            record.put("verified",false);record.put("teaching_event",map());return record;
        }
    }
    static List<Event> extract(String raw) {
        String source=ProfessionalEvidence.normalized(raw);List<Event> result=new ArrayList<>();Event current=null;
        List<Map<String,Object>> outside=new ArrayList<>();
        boolean excluded=false;
        Matcher lines=Pattern.compile("[^\\r\\n]*(?:\\r\\n|\\r|\\n|$)").matcher(source);
        while(lines.find()) {
            if(lines.start()==source.length())break;
            String rawLine=lines.group().replaceFirst("[\\r\\n]+$",""),line=rawLine.strip();
            if(line.isBlank()) {if(current!=null){result.add(current);current=null;}continue;}
            if(EvidenceSections.boundary(line)) {
                if(current!=null){result.add(current);current=null;}
                excluded=line.matches(".*(?:团队|机构|公司|未授课|未讲授|尚未|仅参训|参训|助教|筹备|未来|计划).*");continue;
            }
            Matcher field=FIELD.matcher(line);boolean matched=field.matches();String key=matched?LABELS.get(field.group(1)):null;
            if("course".equals(key)) {
                if(current!=null)result.add(current);
                current=new Event(source,lines.start());
                if(excluded)current.issues.add("excluded_or_nonpersonal_section");
            }
            if(current==null) {
                // A table's omitted preface or later correction may redefine
                // who "本人" is. Do not erase it at a blank line. Decoration and
                // labelled personal metadata carry no teaching assertions.
                if(!line.matches("[\\p{P}\\p{S}\\s]+")&&!ProfessionalEvidence.personalMetadataLine(line)) {
                    int at=lines.start()+rawLine.indexOf(line);
                    outside.add(new Field("未结构化上下文",line,at,at+line.length()).map(source));
                }
                continue;
            }
            current.end=lines.start()+rawLine.length();
            if(key==null) {
                if(NarrativeTeachingEvents.supportingClause(line))current.relations.add(new Field("授课活动说明",line,lines.start()+rawLine.indexOf(line),lines.start()+rawLine.indexOf(line)+line.length()).map(source));
                else current.issues.add("unparsed_event_context");
                continue;
            }
            String value=field.group(2).strip();int at=lines.start()+rawLine.indexOf(line)+field.start(2)+field.group(2).indexOf(value);
            if(value.isBlank()||value.contains("；")||value.contains(";")||value.contains("：")||value.contains(":"))current.issues.add("empty_or_nested_field:"+key);
            Field previous=current.fields.putIfAbsent(key,new Field(field.group(1),value,at,at+value.length()));
            if(previous!=null&&!previous.value.equals(value))current.issues.add("conflicting_field:"+key);
        }
        if(current!=null)result.add(current);
        for(Event event:result) {
            NarrativeTeachingEvents.compositeRole(event);
            if(!outside.isEmpty()){event.issues.add("unparsed_document_context");event.unparsedContext.addAll(outside);}
            if(EvidenceSections.globalQualification(source))event.issues.add("unresolved_global_qualification");
            String text=source.substring(event.start,event.end);
            // Only fully bound teaching-activity lines may contain a worked
            // "示例" without turning the whole biography into an example.
            text=String.join("\n",Arrays.stream(text.split("\\R")).map(line->NarrativeTeachingEvents.supportingClause(line)?line.replace("示例","例题"):line).toList());
            if(ProfessionalEvidence.negative(text)||text.matches("(?s).*(?:不教|不讲|不面向|不针对|除外|仅为|样例|示例|规划|计划中|待核).*") )event.issues.add("qualified_or_unconfirmed_event");
            if(!event.value("actor").isEmpty()&&!event.value("lead_actor").isEmpty()&&!event.value("actor").equals(event.value("lead_actor")))event.issues.add("conflicting_actor_fields");
            if(!event.value("lead_actor").isEmpty()&&!event.value("role").isEmpty()&&!Set.of("主讲","独立主讲","讲授","授课教师").contains(event.value("role")))event.issues.add("conflicting_role_fields");
        }
        result.addAll(NarrativeTeachingEvents.extract(source,result));
        return result;
    }
    static Map<?,?> metadata(Map<?,?> record) {return CityTravelReference.map(record.get("teaching_event"));}
    /** Server-generated literal scopes for the retrieval worker. The response
     * is rechecked against these same source events, never trusted as proof. */
    static List<Map<String,Object>> scoringSections(String raw) {
        String source=ProfessionalEvidence.normalized(raw);List<Map<String,Object>> scopes=new ArrayList<>();
        for(Event e:extract(source)) {
            if(!e.completedPersonalTeaching()||source.codePointCount(e.start,e.end)>220)continue;
            // Its factual fields may be usable, but the rejected utterance must
            // not re-enter retrieval through a full-event embedding. The worker
            // retains the separate literal affirmative units for this paragraph.
            if(!ProfessionalEvidence.methodContrastSpans(source.substring(e.start,e.end)).isEmpty()||
                    !LearnerArtifactContext.spans(source.substring(e.start,e.end)).isEmpty()||
                    e.relations.stream().anyMatch(r->Set.of("owned_example_explanation_context","classroom_instruction_context","learner_practice_instruction_context","auxiliary_material_explanation_context").contains(r.get("relation")))||
                    e.relations.stream().anyMatch(r->"material_description_context_only".equals(r.get("relation"))))continue;
            Map<String,Object> scope=new LinkedHashMap<>();
            int start=source.codePointCount(0,e.start),end=source.codePointCount(0,e.end);
            scope.put("event_id",e.id);scope.put("source_start",start);scope.put("source_end",end);
            scope.put("heading","");scope.put("heading_start",start);scope.put("heading_end",start);
            scope.put("kind",SCORING_SCOPE);scope.put("offset_unit","unicode_code_point");scopes.add(scope);
        }
        return scopes.size()<=64?scopes:List.of();
    }
    static boolean verifiedScoringSections(String source,Map<?,?> details) {
        if(!Boolean.TRUE.equals(details.get("scoped_evidence_complete"))||!SCORING_SCOPE.equals(details.get("scoring_scope"))||
            !(details.get("scoring_sections") instanceof List<?> supplied)||supplied.isEmpty()||supplied.size()>64)return false;
        List<Map<String,Object>> expected=scoringSections(source);Set<String> seen=new HashSet<>();
        for(Object value:supplied) {
            if(!(value instanceof Map<?,?> row))return false;boolean found=false;
            for(Map<String,Object> known:expected) {
                boolean same=true;
                for(String key:List.of("event_id","heading","kind","offset_unit"))same&=Objects.equals(row.get(key),known.get(key));
                for(String key:List.of("source_start","source_end","heading_start","heading_end"))same&=row.get(key) instanceof Number n&&Double.isFinite(n.doubleValue())&&n.doubleValue()==((Number)known.get(key)).longValue();
                if(same){found=seen.add(known.get("event_id").toString());break;}
            }
            if(!found)return false;
        }
        return true;
    }
    static String field(Map<?,?> record,String key) {
        return Objects.toString(CityTravelReference.map(CityTravelReference.map(metadata(record).get("fields")).get(key)).get("value"),"");
    }
    static boolean structured(Map<?,?> record) {return VERSION.equals(metadata(record).get("schema_version"));}
    private static boolean usableRecord(Map<?,?> record) {
        return structured(record)&&metadata(record).get("issues") instanceof List<?> issues&&issues.isEmpty()&&!field(record,"course").isBlank();
    }
    static boolean supportsTopic(Map<?,?> record,List<String> terms) {
        return usableRecord(record)&&RequirementCoverage.contains(field(record,"course"),terms);
    }
    static boolean supportsFeature(Map<?,?> record,String label,List<String> terms) {
        if(!usableRecord(record))return false;
        String key=label.startsWith("授课对象：")||Set.of("老年学员","行政人员","零基础教学").contains(label)?"audience":label.contains("授课形式")||label.equals("线上实时教学")?"mode":
            label.contains("授课语言")||label.equals("中文授课")||label.equals("英文授课")?"language":"learner_activity";
        String value=field(record,key);
        if(value.matches("(?s).*(?:而非|而不是|除外|不含).*") )return false;
        if(label.startsWith("授课对象："))return !ProfessionalEvidence.negative(value)&&
            AudienceContract.compare(label.substring("授课对象：".length()),value).supported();
        if(InstructorMode.applies(label))return InstructorMode.field(value,label);
        if(key.equals("audience")&&terms.stream().anyMatch(t->value.contains("非"+t)||value.contains(t+"除外")))return false;
        if(label.equals("现场完成练习"))return RequirementConstraints.learnerCompletion(value);
        if(key.equals("learner_activity")&&!value.matches("^(?:学员|学生|参训人员).*"))return false;
        return !value.isBlank()&&RequirementCoverage.contains(value,terms)&&!ProfessionalEvidence.negative(value);
    }
    static boolean supportsBound(Map<?,?> record,String unit,int minimum,boolean each) {
        if(!usableRecord(record))return false;
        String value=field(record,each?"each_duration":List.of("场","次","期").contains(unit)?"count":"duration");
        Matcher number=Pattern.compile("("+RequirementConstraints.NUM+")\\s*(分钟|小时|课时|场|次|期)").matcher(value);
        if(!number.matches())return false;int count=RequirementConstraints.number(number.group(1));String actual=number.group(2);
        if(unit.equals("分钟")&&actual.equals("小时"))return count*60>=minimum;
        if(unit.equals("小时")&&actual.equals("分钟"))return count>=minimum*60;
        return actual.equals(unit)&&count>=minimum;
    }
    private TeachingEvents() {}
}
