package com.training;

import java.util.*;

/** Development pairs authored before entity-binding implementation; not blind. */
public final class NarrativeEntityBindingTest {
    static int checks;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static void positive(String source,String course,String audience,String label) {
        List<TeachingEvents.Event> events=TeachingEvents.extract(source);
        TeachingEvents.Event e=events.stream().filter(x->x.completedPersonalTeaching()&&x.value("course").equals(course)).findFirst().orElse(null);
        check(e!=null,label+": personal complete event");if(e==null)return;
        check(e.value("audience").equals(audience),label+": exact audience binding");
        check(Boolean.TRUE.equals(e.map().get("independent_lead")),label+": independent modifies teaching");
        check(!TeachingEvents.scoringSections(source).isEmpty(),label+": bounded source scope");
        for(TeachingEvents.Field field:e.fields.values())check(source.substring(field.start(),field.end()).equals(field.value()),label+": field source span");
    }
    static void negative(String source,String label) {
        check(TeachingEvents.extract(source).stream().noneMatch(TeachingEvents.Event::completedPersonalTeaching),label);
        check(TeachingEvents.scoringSections(source).isEmpty(),label+": no qualified scope");
        for(TeachingEvents.Event e:TeachingEvents.extract(source))if(!e.usable())
            check(!TeachingEvents.supportsFeature(e.record(),"授课对象：新学员",List.of("新学员")),label+": unusable event cannot support features");
    }
    public static void main(String[] args) {
        String[][] domains={{"陶泥接缝观察","新入职的工作室助手"},{"展柜湿度登记","第一次参加实习的展馆人员"},{"天文底片编号","新到岗的观测助理"}};
        for(String[] d:domains) {
            String course=d[0],audience=d[1],head="中心为"+audience+"开设《"+course+"》。";
            positive(head+"这些组织工作不涉及课堂讲解。这堂面授课由我独立讲完。",course,audience,"passive:"+course);
            positive(head+"中心准备教室、材料并通知学员。这些组织工作不涉及课堂讲解。这堂面授课由我独立讲完。",course,audience,"organization logistics:"+course);
            positive(head+"我给"+audience+"独立讲完了这门课的全部内容。班级已经结业。",course,audience,"active modifier:"+course);
            positive("参加本次训练的是"+audience+"。学习内容定为《"+course+"》。采用面授。开班后，我独立讲完了这门课的全部内容。班级已经结业。",course,audience,"preface entity:"+course);
            negative(head+"这些组织工作不涉及课堂讲解。这堂面授课由同事独立讲完，我没有授课。","other actor:"+course);
            negative(head+"本人不涉及课堂讲解。班级已经结业。","own denial:"+course);
            negative(head+"班级已经结业。","organization completion not teacher:"+course);
            check(TeachingEvents.extract(head+"班级已经结业。").stream().noneMatch(TeachingEvents.Event::usable),"organization entity is not personal capability:"+course);
            negative(head+"中心准备教室、材料并通知学员。班级已经结业。","logistics not teaching:"+course);
            negative(head+"中心准备教室并安排同事教学。这堂面授课由我独立讲完。","unknown organizational role:"+course);
            negative(head+"我给"+audience+"独立完成了这门课的讲义。班级已经结业。","materials not teaching:"+course);
            negative(head+"另开设《设备整理方法》。这堂面授课由我独立讲完。","two-course pronoun ambiguity:"+course);
            negative(head+"这堂面授课由我独立讲完。本人并未完成该课程的教学。","same-course denial:"+course);
            negative("以下段落中的第一人称代表同事。"+head+"这堂面授课由我独立讲完。","represented actor:"+course);
            negative(head+"\n\n这堂面授课由我独立讲完。","cross-paragraph unbound:"+course);
            negative(head+"我仅担任助教。这堂面授课由我独立讲完。","assistant conflict:"+course);
            negative(head+"计划中的安排是这堂面授课由我独立讲完。","future passive not actual:"+course);
            negative("中心明年将为"+audience+"开设《"+course+"》。这门课由我独立讲完。","future declaration frame:"+course);
            negative("如果中心为"+audience+"开设《"+course+"》，这门课由我独立讲完。","conditional declaration frame:"+course);
            for(String modal:List.of("将","计划","打算","准备"))negative("中心"+modal+"开设《"+course+"》。这门课由我独立讲完。","modal declaration frame:"+modal+course);
            negative("转述张老师在中心为"+audience+"开设《"+course+"》。这门课由我独立讲完。","attributed declaration frame:"+course);
            negative("中心开设《"+course+"》。这门课由我独立讲完。本班学员为老员工而非"+audience+"。","excluded audience is not positive proof:"+course);
        }
        positive("参加本次训练的是刚到岗实习的展馆人员。学习内容定为《展柜湿度登记》。采用面授。开班后，我为这批实习生独立讲完了这门课的全部内容。班级已经结业。","展柜湿度登记","刚到岗实习的展馆人员","bounded audience reference");
        negative("中心为新员工开设《展柜湿度登记》。我给这些老员工独立讲完这门课的全部内容。","contradictory audience reference");
        negative("中心为非实习人员开设《展柜湿度登记》。我给这些实习生独立讲完这门课的全部内容。","negated nominal core is not an alias");
        System.out.println("Narrative entity binding checks="+checks);
    }
}
