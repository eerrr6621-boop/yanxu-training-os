package com.training;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Authored method-content counterexamples, not model-accuracy measurement. */
public final class MethodContrastTest {
    static int checks;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static Map<String,Object> inspect(String raw){
        String source=ProfessionalEvidence.normalized(raw);
        Map<String,Object> result=new LinkedHashMap<>();result.put("text",source);
        result.put("omitted",ProfessionalEvidence.methodContrastSpans(source).stream().map(s->List.of(source.codePointCount(0,s[0]),source.codePointCount(0,s[1]))).toList());
        result.put("positive",ProfessionalEvidence.units(source));result.put("negative",ProfessionalEvidence.negative(source));
        result.put("records",ProfessionalEvidence.records(source));return result;
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception{
        if(args.length>0&&args[0].equals("--inspect")){
            List<Object> values=(List<Object>)Json.parse(new String(System.in.readAllBytes(),StandardCharsets.UTF_8));
            System.out.println(Json.write(values.stream().map(v->inspect((String)v)).toList()));return;
        }
        for(String topic:List.of("客户服务","古籍页码核对","园艺工具清洁")){
            String lead="本人曾主讲《"+topic+"》。";
            String method="方法先演示操作步骤，再指出动作需要调整，";
            for(String quote:List.of("“挺好”","「可以」","\"不错\"","“👍不错”"))for(String verb:List.of("评价","说","回答","反馈")){
                String contrast="而不是只"+verb+quote;
                String source=ProfessionalEvidence.normalized(lead+method+contrast+"。我随后讲解改进步骤。");
                List<int[]> spans=ProfessionalEvidence.methodContrastSpans(source);
                check(spans.size()==1,"bounded method contrast "+quote+verb);
                check(source.substring(spans.get(0)[0],spans.get(0)[1]).equals(contrast),"whole literal contrast");
                check(!ProfessionalEvidence.negative(source),"not history denial");
                check(ProfessionalEvidence.allowed(source),"paragraph recovers without weakening actor guards");
                check(ProfessionalEvidence.positive(source).contains(topic),"course retained");
                check(!ProfessionalEvidence.positive(source).contains(quote),"rejected utterance not positive evidence");
                check(ProfessionalEvidence.records(source).stream().anyMatch(r->source.equals(r.get("context_text"))),"full source context retained");
            }
            String positive=ProfessionalEvidence.normalized(lead+method+"而不是只评价“挺好”。");
            for(String tail:List.of("本人没有主讲上述课程。","实际由同事主讲。","本人仅担任助教。","以上只是模板，不代表本人经历。")){
                String source=positive+tail;
                check(!ProfessionalEvidence.allowed(source),"real qualification remains "+tail);
                check(ProfessionalEvidence.positive(source).isEmpty(),"no laundered prefix "+tail);
            }
            for(String q:List.of("“本人已经讲完”","“同事主讲”","“没有授课”","“教学经历属实”","“尚未完成”","“计划授课”","“挺好","“挺好”但未主讲","“挺好”，实际上本人没有主讲"))
                check(ProfessionalEvidence.methodContrastSpans(ProfessionalEvidence.normalized(lead+method+"而不是只评价"+q+"。")).isEmpty(),"ambiguous/factual quotation stays blocked "+q);
            for(String prior:List.of("",lead+"\n\n","同事曾主讲《"+topic+"》。","本人参加《"+topic+"》培训。","以下转述原话："+lead))
                check(ProfessionalEvidence.methodContrastSpans(ProfessionalEvidence.normalized(prior+method+"而不是只评价“挺好”。")).isEmpty(),"needs prior own teaching in same paragraph");
            for(String prefix:List.of("本人说明课程经历，","方法是同事说明具体步骤，","方法是假设本人说明具体步骤，","方法先说明具体步骤，但是没有授课，"))
                check(ProfessionalEvidence.methodContrastSpans(ProfessionalEvidence.normalized(lead+prefix+"而不是只评价“挺好”。")).isEmpty(),"ambiguous method context");
            check(ProfessionalEvidence.methodContrastSpans(ProfessionalEvidence.normalized(method+"而不是只评价“挺好”。"+lead)).isEmpty(),"no borrowing a later teacher claim");
        }
        System.out.println("Method contrast checks: "+checks);
    }
}
