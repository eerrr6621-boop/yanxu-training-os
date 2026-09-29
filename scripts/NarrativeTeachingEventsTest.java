package com.training;

import java.util.*;

/** Fixed synthetic predicate/argument tests, not an accuracy claim. */
public final class NarrativeTeachingEventsTest {
    static int checks;static List<String> failures=new ArrayList<>();
    static void check(boolean value,String label){checks++;if(!value)failures.add(label);}
    static boolean supported(String source,String topic,String audience) {
        return TeachingEvents.extract(source).stream().anyMatch(e->e.completedPersonalTeaching()&&e.narrativeIndependent&&
            e.value("course").contains(topic)&&(audience.isBlank()||e.value("audience").equals(audience)));
    }
    static void spans(String source) {
        String normalized=ProfessionalEvidence.normalized(source);
        for(TeachingEvents.Event event:TeachingEvents.extract(source)) {
            Map<String,Object> map=event.map();check(map.get("text").equals(normalized.substring(event.start,event.end)),"literal event");
            List<Map<String,Object>> fields=new ArrayList<>();event.fields.values().forEach(f->fields.add(f.map(normalized)));fields.addAll(event.relations);
            for(Map<String,Object> f:fields) {
                int a=normalized.offsetByCodePoints(0,((Number)f.get("source_start")).intValue()),b=normalized.offsetByCodePoints(0,((Number)f.get("source_end")).intValue());
                check(f.get("text").equals(normalized.substring(a,b)),"literal field/relation");
                check(a>=0&&b<=normalized.length(),"bounded offsets");
            }
        }
        List<Map<String,Object>> scopes=TeachingEvents.scoringSections(normalized);
        if(!scopes.isEmpty()) {
            Map<String,Object> details=new LinkedHashMap<>(Map.of("scoped_evidence_complete",true,"scoring_scope",TeachingEvents.SCORING_SCOPE,"scoring_sections",scopes));
            check(EvidenceSections.verified(normalized,details),"Java independently rechecks source events");
            Map<String,Object> bad=new LinkedHashMap<>(scopes.get(0));bad.put("source_end",((Number)bad.get("source_end")).intValue()-1);
            details.put("scoring_sections",List.of(bad));check(!EvidenceSections.verified(normalized,details),"worker cannot truncate an event");
            details.put("scoring_sections",scopes);
            check(!EvidenceSections.verified(normalized+"\n以上不是本人授课经历。",details),"worker scope cannot erase document denial");
            check(!EvidenceSections.verified(normalized.replace("本人","同事").replace("我","同事"),details),"worker scope cannot change teaching actor");
        }
    }
    public static void main(String[] args) {
        for(String[] values:List.of(new String[]{"仓储托盘标记核对","初次承担仓储工作的人员"},new String[]{"草木拓印构图","社区活动参与者"})) {
            String t=values[0],aud=values[1];
            List<String> positives=List.of(
                "本人已独立主讲"+t+"。",
                "我独立讲授了《"+t+"》。这次课程已经结束。",
                t+"课已经结束，由本人从头到尾独立主讲。我用两份样本讲解检查流程。工作室只提供场地和材料。",
                t+"课程已经讲完，教学由本人独立完成。我讲解了操作次序。其他项目成员只承担材料运输。",
                "教学记录：本人已独立完成"+t+"教学。这一班已结课，我讲解了操作要点并负责课堂最后的复盘答疑。",
                t+"课的结课小结：本人独立完成了这门课的全部讲授。我演示操作步骤。这里所说的这门课就是"+t+"。",
                t+"班已经结课。本人独立主讲，本班全部学员都是"+aud+"。我从流程的第一步讲起，再带他们完成一次示范。",
                "本人已独立面向"+aud+"讲授过"+t+"。",
                "课程名称："+t+"\n完成情况：已结课\n授课人：本人，独立完成全课讲授\n本班对象："+aud+"\n我用两份示例，讲清实际操作顺序。",
                "上次社区工作坊里，我独立讲授了"+t+"。先示范如何检查，再教大家如何改正；这次课程已经结束。"
            );
            for(int i=0;i<positives.size();i++){String s=positives.get(i);check(supported(s,t,""),"positive "+t+" #"+i);if(args.length>0&&!supported(s,t,""))System.out.println(Json.write(TeachingEvents.extract(s).stream().map(TeachingEvents.Event::map).toList()));spans(s);}
            check(supported(positives.get(6),t,aud),"same event audience "+t);
            check(supported(positives.get(7),t,aud),"active predicate audience "+t);
            check(!TeachingEvents.scoringSections(positives.get(8)).isEmpty(),"field-block activity retains source scope "+t);
            check(supported(positives.get(8).replace("两份示例","模拟图"),t,aud),"simulation teaching material is not planned teaching "+t);
            check(!supported(positives.get(8).replace("已结课","拟开课"),t,aud),"planned state is not a completed class "+t);
            List<String> negatives=List.of(
                "我计划独立主讲"+t+"。",
                "本人已完成"+t+"教学材料。",
                "同事已独立主讲"+t+"，本人只提供材料。",
                "本人已独立主讲"+t+"。以上内容只是模板，不代表本人经历。",
                t+"课程已经结课，主讲人是同事。本人全程旁听。",
                t+"课程已经结束，由本人独立主讲。更正：实际由其他教师主讲。",
                "这是同事写的第一人称经历本人已独立主讲"+t+"。",
                t+"课程已经结束。我独立完成了这门课的备课。",
                "本人已独立主讲另一课程。"+t+"由同事讲授。",
                "课程名称："+t+"\n完成情况：已结课\n授课人：本人，独立完成全课讲授\n授课角色：助教"
            );
            for(int i=0;i<negatives.size();i++)check(!supported(negatives.get(i),t,""),"negative "+t+" #"+i);
        }
        System.out.println(Json.write(Map.of("suite","narrative-teaching-events","checks",checks,"failures",failures,"model_executed",false)));
        if(!failures.isEmpty())throw new AssertionError(failures.size()+" narrative checks failed");
    }
}
