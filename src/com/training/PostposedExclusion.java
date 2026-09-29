package com.training;

import java.util.*;
import java.util.regex.Pattern;

/** A comma-linked rejected antecedent is not a positive requirement.
 * This locates literal scope only; it never resolves or enforces an exclusion. */
final class PostposedExclusion {
    record Span(int start,int end) {}
    private record Part(int start,int end) {}
    private static final Pattern PREDICATE=Pattern.compile("^(?:不算|不计入|不计作|不作为|不满足|不能视为|不能算作|不能代替)\\S.*",Pattern.DOTALL|Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern COMMA_GAP=Pattern.compile("[\\t \\r\\n]*[，,][\\t \\r\\n]*");
    static List<Span> spans(String source) {
        List<Part> parts=new ArrayList<>();Deque<Character> close=new ArrayDeque<>();int start=0;
        for(int i=0;i<source.length();i++) {
            char c=source.charAt(i);
            if(!close.isEmpty()&&c==close.peek()){close.pop();continue;}
            Character end=switch(c){case '《'->'》';case '“'->'”';case '（'->'）';case '('->')';case '"'->'"';default->null;};
            if(end!=null){close.push(end);continue;}
            if("》”）)".indexOf(c)>=0)return List.of();
            if(close.isEmpty()&&"，,。；;！？!?\r\n".indexOf(c)>=0){add(parts,source,start,i);start=i+1;}
        }
        if(!close.isEmpty())return List.of();add(parts,source,start,source.length());
        List<Span> result=new ArrayList<>();
        for(int i=1;i<parts.size();i++) {
            Part previous=parts.get(i-1),current=parts.get(i);
            if(!COMMA_GAP.matcher(source.substring(previous.end,current.start)).matches() ||
                !PREDICATE.matcher(source.substring(current.start,current.end)).matches())continue;
            int left=previous.start;
            if(!result.isEmpty()&&result.get(result.size()-1).end>=left)left=result.remove(result.size()-1).start;
            result.add(new Span(left,current.end));
        }
        return List.copyOf(result);
    }
    private static void add(List<Part> parts,String source,int start,int end) {
        while(start<end&&Character.isWhitespace(source.charAt(start)))start++;
        while(end>start&&Character.isWhitespace(source.charAt(end-1)))end--;
        if(end>start)parts.add(new Part(start,end));
    }
    static String masked(String source,List<Span> spans) {
        char[] view=source.toCharArray();
        for(Span span:spans)for(int i=span.start;i<span.end;i++)if(view[i]!='\r'&&view[i]!='\n')view[i]=' ';
        return new String(view);
    }
    static boolean overlaps(List<Span> spans,int start,int end) {
        return spans.stream().anyMatch(s->s.start<end&&s.end>start);
    }
}
