package com.training;

import java.util.*;
import java.util.regex.*;

/** Literal physical-line ranges; a trailing comma keeps its continuation. */
final class PhysicalContinuation {
    record Line(int start,int end) {
        String text(String source){return source.substring(start,end);}
    }
    static List<Line> lines(String source) {
        List<Line> physical=new ArrayList<>(),result=new ArrayList<>();int start=0;
        Matcher breaks=Pattern.compile("\\R").matcher(source);
        while(breaks.find()){physical.add(new Line(start,breaks.start()));start=breaks.end();}
        physical.add(new Line(start,source.length()));
        for(int i=0;i<physical.size();i++){
            Line first=physical.get(i);int end=first.end();
            while(physical.get(i).text(source).stripTrailing().endsWith(",")&&i+1<physical.size()
                    &&!physical.get(i+1).text(source).isBlank())end=physical.get(++i).end();
            result.add(new Line(first.start(),end));
        }
        return result;
    }
}
