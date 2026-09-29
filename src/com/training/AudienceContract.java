package com.training;

import java.util.*;
import java.util.regex.*;

/** Bounded audience type grammar. Opaque job/organisation text is never a
 * domain synonym. This compares explicit audience claims, not teacher identity. */
final class AudienceContract {
    static final String VERSION="audience_contract_v1";
    private static final Pattern BATCH=Pattern.compile("^(新一批|新的一批|这一批|这批|本批)(?:的)?");
    private static final Pattern NEW_HIRE=Pattern.compile("^(.*?)(新入职的?|刚入职的?)(.+)$");
    private static final Pattern INTERNSHIP_RELATIVE=Pattern.compile("^(刚)?到(.{1,40}?)实习的(.{2,40})$");
    private static final Pattern INTERNSHIP_COMPACT=Pattern.compile("^(.{0,40}?)实习(?:的)?(.{2,40})$");
    private static final Pattern DECLARED=Pattern.compile("^(?:授课对象|培训对象|参训对象|学员对象|受众|本班对象)[:：]\\s*(.+)$");
    private static final Pattern POLARITY=Pattern.compile("而非|而不是|除外|不含|不包括|不面向|不针对|不是|并非|(?:非|未|不|无|没有|尚未)(?:实习|新入职|刚入职)");
    record Span(String kind,String text,int start,int end) {
        Map<String,Object> map(String source) {return Map.of("kind",kind,"text",text,"source_start",source.codePointCount(0,start),"source_end",source.codePointCount(0,end),"source","normalized_audience_value","offset_unit","unicode_code_point");}
    }
    record Group(String raw,Span span,String context,String role,String status,boolean recentArrival,
                 List<Span> components,List<String> issues) {
        String identity(){return context+role;}
        Map<String,Object> map(String source) {return Map.of("raw",raw,"span",span.map(source),"context_literal",context,"role_literal",role,"status",status,
            "recent_arrival",recentArrival,"components",components.stream().map(x->x.map(source)).toList(),"issues",issues);}
    }
    record Contract(String source,List<Group> groups,List<String> issues) {
        Map<String,Object> map() {return Map.of("schema_version",VERSION,"source",source,"offset_source","normalized_audience_value",
            "comparison_purpose","audience_type_not_same_individuals","groups",groups.stream().map(g->g.map(source)).toList(),"issues",issues);}
    }
    record Comparison(String status,String reason,Contract requirement,Contract evidence) {
        boolean supported(){return status.equals("source_supported");}
        Map<String,Object> map(){return Map.of("status",status,"reason",reason,"requirement",requirement.map(),"evidence",evidence.map(),"verified",false);}
    }
    /** Preserve existing List signatures without serialising canonical strings
     * as new requirements. The only visible list entry remains the full input. */
    static final class Terms extends AbstractList<String> {
        final Contract contract;
        Terms(String source){contract=parse(source);}
        @Override public String get(int index){if(index!=0)throw new IndexOutOfBoundsException(index);return contract.source;}
        @Override public int size(){return 1;}
    }
    static List<String> terms(String source){return new Terms(source);}
    static Contract parse(String raw) {
        String source=ProfessionalEvidence.normalized(raw).strip().replaceFirst("[。]+$","");
        List<String> issues=new ArrayList<>();List<Group> groups=new ArrayList<>();
        if(source.isEmpty()||source.length()>240)return new Contract(source,List.of(),List.of("empty_or_long_audience"));
        int depth=0,start=0;
        for(int i=0;i<source.length();i++) {
            char c=source.charAt(i);
            if(c=='(')depth++;else if(c==')'&&--depth<0)issues.add("unbalanced_qualifier");
            if(depth==0&&"、；;".indexOf(c)>=0) {groups.add(group(source,start,i));start=i+1;}
            if(depth==0&&c==':')issues.add("unparsed_nested_field");
            if("\r\n".indexOf(c)>=0)issues.add("multiline_audience_not_atomic");
        }
        if(depth!=0)issues.add("unbalanced_qualifier");groups.add(group(source,start,source.length()));
        return new Contract(source,List.copyOf(groups),List.copyOf(issues));
    }
    private static Group group(String source,int begin,int end) {
        while(begin<end&&Character.isWhitespace(source.charAt(begin)))begin++;
        while(end>begin&&Character.isWhitespace(source.charAt(end-1)))end--;
        String raw=source.substring(begin,end),value=raw;int at=begin;
        List<Span> components=new ArrayList<>();List<String> issues=new ArrayList<>();
        Matcher batch=BATCH.matcher(value);
        if(batch.find()){components.add(new Span("cohort_reference_not_seniority",batch.group(),at,at+batch.end()));at+=batch.end();value=value.substring(batch.end());}
        if(raw.matches(".*(?:同一批|同一组|原班|指定名单).*"))issues.add("same_individuals_not_proved_by_audience_type");
        if(POLARITY.matcher(raw).find())issues.add("audience_negation_or_exclusion");
        if(value.isBlank())issues.add("missing_audience_type");
        String context="",role=value,status="unspecified";boolean arrival=false;
        Matcher relative=INTERNSHIP_RELATIVE.matcher(value),hire=NEW_HIRE.matcher(value),intern=INTERNSHIP_COMPACT.matcher(value);
        if(relative.matches()) {
            context=relative.group(2);role=relative.group(3);status="internship";arrival=relative.group(1)!=null;
            components.add(new Span("context_literal",context,at+relative.start(2),at+relative.end(2)));
            components.add(new Span("role_literal",role,at+relative.start(3),at+relative.end(3)));
            int predicate=at+relative.end(2);components.add(new Span("internship_relation","实习",predicate,predicate+2));
            components.add(new Span("arrival_relation",value.substring(0,relative.start(2)),at,at+relative.start(2)));
        } else if(hire.matches()) {
            context=hire.group(1);role=hire.group(3);status="new_hire";
            components.add(new Span("context_literal",context,at+hire.start(1),at+hire.end(1)));
            components.add(new Span("new_hire_modifier",hire.group(2),at+hire.start(2),at+hire.end(2)));
            components.add(new Span("role_literal",role,at+hire.start(3),at+hire.end(3)));
        } else if(intern.matches()) {
            context=intern.group(1);role=intern.group(2);status="internship";
            components.add(new Span("context_literal",context,at+intern.start(1),at+intern.end(1)));
            components.add(new Span("internship_relation","实习",at+intern.end(1),at+intern.end(1)+2));
            components.add(new Span("role_literal",role,at+intern.start(2),at+intern.end(2)));
        } else components.add(new Span("opaque_audience_type",value,at,end));
        // Qualifiers not represented by the small relation grammar remain in
        // context/role literal strings. They are not stripped from comparison.
        return new Group(raw,new Span("audience_group",raw,begin,end),context,role,status,arrival,List.copyOf(components),List.copyOf(issues));
    }
    static Comparison compare(String requirement,String evidence){return compare(parse(requirement),parse(evidence));}
    static boolean typeComparable(Contract contract) {
        return contract.issues.isEmpty()&&!contract.groups.isEmpty()&&contract.groups.stream().allMatch(g->g.issues.isEmpty());
    }
    static Comparison compare(Contract requirement,Contract evidence) {
        if(!requirement.issues.isEmpty()||!evidence.issues.isEmpty())return new Comparison("needs_evidence","unparsed_audience_boundary",requirement,evidence);
        if(!typeComparable(requirement)||!typeComparable(evidence))
            return new Comparison("needs_evidence","qualified_or_unresolved_audience_group",requirement,evidence);
        for(Group wanted:requirement.groups) {
            boolean supported=false;
            for(Group actual:evidence.groups)if(matches(wanted,actual)){supported=true;break;}
            if(!supported)return new Comparison("needs_evidence","audience_role_status_or_literal_qualifier_missing",requirement,evidence);
        }
        return new Comparison("source_supported","same_group_explicit_type_conditions",requirement,evidence);
    }
    private static boolean matches(Group wanted,Group actual) {
        if(wanted.recentArrival&&!actual.recentArrival)return false;
        if(wanted.status.equals("unspecified"))return wanted.role.equals(actual.identity());
        return wanted.status.equals(actual.status)&&wanted.role.equals(actual.role)&&wanted.context.equals(actual.context);
    }
    /** Only labelled source-audience fields may receive structural rewrites.
     * A legacy non-field clause keeps its old complete literal matching path. */
    static String declaredValue(String clause) {
        Matcher field=DECLARED.matcher(clause.strip());return field.matches()?field.group(1).strip():null;
    }
    private AudienceContract(){}
}
