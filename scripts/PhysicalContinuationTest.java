package com.training;
import java.util.*;

/** A layout break must not erase a qualification or manufacture an excerpt. */
public final class PhysicalContinuationTest {
    static int checks;
    static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    public static void main(String[] args){
        String lead="我曾给资料管理员完整讲授《🌿标本标签核对》，授课方式是到场面授。";
        for(String newline:List.of("\n","\r\n"))for(String indent:List.of("","  ","\t")){
            String raw=lead+"学员带走的是空白笔记模板，"+newline+indent+"不是客户信息。";
            String source=ProfessionalEvidence.normalized(raw);
            List<PhysicalContinuation.Line> lines=PhysicalContinuation.lines(source);
            check(lines.size()==1,"one contiguous inspection group");
            check(lines.get(0).text(source).equals(source),"all original whitespace retained");
            List<Map<String,Object>> records=ProfessionalEvidence.records(source);
            check(!records.isEmpty(),"teaching record recovers");
            for(Map<String,Object> record:records){
                String text=(String)record.get("text");int at=((Number)record.get("source_offset")).intValue();
                check(source.substring(at,at+text.length()).equals(text),"exact source quote");
                check(!text.contains("笔记模板")&&!text.contains("客户信息"),"no artifact proof");
                check(source.equals(record.get("context_text")),"both physical halves retained as context");
                check(Boolean.FALSE.equals(record.get("verified")),"no invented verification");
            }
            check(TeachingEvents.extract(source).stream().anyMatch(TeachingEvents.Event::completedPersonalTeaching),"original event usable");
            for(String denial:List.of("但本人没有讲授上述课程。","本人仅担任助教。","实际由同事讲授。")){
                String limited=ProfessionalEvidence.normalized(lead+","+newline+indent+denial);
                check(PhysicalContinuation.lines(limited).size()==1,"denial belongs to same inspection");
                check(ProfessionalEvidence.positive(limited).isEmpty(),"no affirmative half survives denial");
                check(TeachingEvents.extract(limited).stream().noneMatch(TeachingEvents.Event::completedPersonalTeaching),"no eligible event");
            }
            String blank=ProfessionalEvidence.normalized(lead+"学员带走的是笔记模板，"+newline+indent+newline+"不是客户信息。");
            check(LearnerArtifactContext.spans(blank).isEmpty(),"artifact does not bridge blank line");
            check(ProfessionalEvidence.positive(blank).isEmpty(),"dangling comma not a complete statement");
        }
        for(String newline:List.of("\n","\r\n","\r","\u2028","\u2029")){
            String text="首行,"+newline+"  次行,"+newline+"末行。";
            var lines=PhysicalContinuation.lines(text);
            check(lines.size()==1&&lines.get(0).text(text).equals(text),"line separator retained");
            String repeated="首行,"+newline+"续行。"+newline+"首行,"+newline+"续行。";
            var groups=PhysicalContinuation.lines(repeated);
            check(groups.size()==2,"repeated line groups separate");
            check(groups.get(1).start()>groups.get(0).end(),"repeated ranges not first indexOf");
        }
        check(ProfessionalEvidence.positive(lead+",").isEmpty(),"EOF dangling comma withheld");
        String incomplete=ProfessionalEvidence.normalized(lead+"学员带走的是空白笔记模板，不是客户信息。后续说明，");
        List<String> pieces=ProfessionalEvidence.affirmativeParts(incomplete);
        check(pieces.size()>1,"recognized cut has a remaining raw fragment");
        check(pieces.get(pieces.size()-1).endsWith(","),"raw end comma not trimmed as a known cut");
        check(ProfessionalEvidence.positive(incomplete).isEmpty(),"raw incomplete context remains withheld");
        String ordinary=ProfessionalEvidence.normalized("主讲课程：\n标签核对\n\n个人介绍：\n记录整理");
        check(PhysicalContinuation.lines(ordinary).size()==5,"ordinary headings and blank boundaries retained");
        for(String topic:List.of("客户服务","古籍页码核对","园艺工具清洁")){
            String source="本人曾主讲《"+topic+"》。方法先演示操作步骤，再指出动作需要调整，而不是只评价“挺好”。";
            String quote=ProfessionalEvidence.positive(source);
            check(!quote.isEmpty()&&!quote.endsWith(","),"approved cut does not leave a comma");
            check(ProfessionalEvidence.positive(quote).equals(quote),"second-pass display retains safe quote");
            check(ProfessionalEvidence.normalized(source).contains(quote),"trimmed quote remains exact source");
        }
        System.out.println("Physical continuation checks: "+checks);
    }
}
